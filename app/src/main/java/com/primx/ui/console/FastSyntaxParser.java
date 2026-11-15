package com.primx.ui.console;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Pattern;

public class FastSyntaxParser {

    private static class BlockInfo {
        char bracket;
        int line;
        BlockInfo(char bracket, int line) {
            this.bracket = bracket;
            this.line = line;
        }
    }

    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#([A-Fa-f0-9]{3}|[A-Fa-f0-9]{4}|[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$");
    private static final Pattern DIMENSION_PATTERN = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?(dp|sp|px|in|mm|pt)$");
    private static final Pattern RESOURCE_REF_PATTERN = Pattern.compile("^\\?attr/.*|^@[+]?[a-zA-Z_]+/.*");

    public static List<Console.ErrorItem> analyze(String filePath, String content) {
        List<Console.ErrorItem> errors = new ArrayList<>();
        if (filePath == null || content == null || content.trim().isEmpty()) return errors;

        try {
            if (content.length() > 100000) return null; 

            String lowerPath = filePath.toLowerCase();
            if (lowerPath.endsWith(".java")) {
                List<Console.ErrorItem> javaErrs = checkJavaGrammar(filePath, content);
                if (javaErrs == null) return null; 
                errors.addAll(javaErrs);
            } else if (lowerPath.endsWith(".xml")) {
                List<Console.ErrorItem> xmlErrs = checkXmlGrammar(filePath, content);
                if (xmlErrs == null) return null; 
                errors.addAll(xmlErrs);
            }
        } catch (Exception e) {
            return null;
        }
        return errors;
    }

    private static List<Console.ErrorItem> checkJavaGrammar(String filePath, String content) {
        List<Console.ErrorItem> errors = new ArrayList<>();
        Stack<BlockInfo> stack = new Stack<>();

        int line = 1;
        boolean inString = false, inChar = false, inLineComment = false, inBlockComment = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = (i + 1 < content.length()) ? content.charAt(i + 1) : '\0';

            if (c == '\n') {
                line++;
                if (inLineComment) {
                    inLineComment = false;
                }
                if (inString) {
                    errors.add(new Console.ErrorItem(filePath, line - 1, 0, 1, "Syntax Error: Unclosed string literal (\")", false, false));
                    inString = false;
                }
                if (inChar) {
                    errors.add(new Console.ErrorItem(filePath, line - 1, 0, 1, "Syntax Error: Unclosed character literal (')", false, false));
                    inChar = false;
                }
                continue;
            }

            if (inLineComment) continue;

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inString) {
                if (c == '\\') { i++; continue; } 
                if (c == '"') inString = false;
                continue;
            }

            if (inChar) {
                if (c == '\\') { i++; continue; } 
                if (c == '\'') inChar = false;
                continue;
            }

            if (c == '/' && next == '/') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }

            if (c == '{' || c == '(' || c == '[') {
                stack.push(new BlockInfo(c, line));
                if (stack.size() > 80) return null; 
            } else if (c == '}' || c == ')' || c == ']') {
                if (stack.isEmpty()) {
                    errors.add(new Console.ErrorItem(filePath, line, 0, 1, "Syntax Error: Unexpected '" + c + "' without opening bracket", false, false));
                } else {
                    BlockInfo top = stack.pop();
                    if (!isMatchingBracket(top.bracket, c)) {
                        errors.add(new Console.ErrorItem(filePath, line, 0, 1, "Syntax Error: Expected '" + getMatching(top.bracket) + "' but found '" + c + "'", false, false));
                    }
                }
            }
        }

        if (inBlockComment) {
            errors.add(new Console.ErrorItem(filePath, line, 0, 1, "Syntax Error: Unclosed block comment (/* ... */)", false, false));
        }

        while (!stack.isEmpty()) {
            BlockInfo top = stack.pop();
            errors.add(new Console.ErrorItem(filePath, top.line, 0, 1, "Syntax Error: Unclosed '" + top.bracket + "' at end of file", false, false));
        }

        if (content.contains("public private ") || content.contains("private public ") 
                || content.contains("final abstract ") || content.contains("abstract final ")
                || content.contains("extends implements") || content.contains("implements extends")) {
            return null;
        }

        return errors;
    }

    private static boolean isMatchingBracket(char open, char close) {
        return (open == '{' && close == '}') || (open == '(' && close == ')') || (open == '[' && close == ']');
    }

    private static char getMatching(char open) {
        if (open == '{') return '}';
        if (open == '(') return ')';
        if (open == '[') return ']';
        return '?';
    }

    private static List<Console.ErrorItem> checkXmlGrammar(String filePath, String content) {
        List<Console.ErrorItem> errors = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(content));

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    int attrCount = parser.getAttributeCount();
                    for (int i = 0; i < attrCount; i++) {
                        String attrName = parser.getAttributeName(i);
                        String attrValue = parser.getAttributeValue(i);
                        int line = parser.getLineNumber();

                        if (attrValue == null || attrValue.trim().isEmpty()) {
                            errors.add(new Console.ErrorItem(filePath, line, 0, 1, "XML Error: Attribute '" + attrName + "' cannot be empty.", false, false));
                            continue;
                        }

                        attrValue = attrValue.trim();
                        if (RESOURCE_REF_PATTERN.matcher(attrValue).matches()) continue;

                        String lowerName = attrName.toLowerCase();
                        
                        if (lowerName.contains("color") || lowerName.contains("tint") || attrValue.startsWith("#")) {
                            if (attrValue.startsWith("#") && !HEX_COLOR_PATTERN.matcher(attrValue).matches()) {
                                errors.add(new Console.ErrorItem(filePath, line, 0, 1, "XML Error: Invalid color code format '" + attrValue + "'. Expected #RGB, #ARGB, #RRGGBB, or #AARRGGBB.", false, false));
                            } else if (!attrValue.startsWith("#") && !attrValue.startsWith("@") && !attrValue.startsWith("?")) {
                                errors.add(new Console.ErrorItem(filePath, line, 0, 1, "XML Error: Colors must start with #, @color/ or ?attr/.", false, false));
                            }
                        }

                        if (lowerName.contains("size") || lowerName.contains("width") || lowerName.contains("height") 
                                || lowerName.contains("margin") || lowerName.contains("padding") || lowerName.contains("elevation") || lowerName.contains("radius")) {
                            if (!attrValue.equals("match_parent") && !attrValue.equals("wrap_content") && !attrValue.equals("fill_parent")) {
                                if ((Character.isDigit(attrValue.charAt(0)) || attrValue.startsWith("-")) && !DIMENSION_PATTERN.matcher(attrValue).matches()) {
                                    errors.add(new Console.ErrorItem(filePath, line, 0, 1, "XML Error: Invalid dimension '" + attrValue + "'. Missing unit (dp, sp, px).", false, false));
                                }
                            }
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (XmlPullParserException e) {
            String rawMsg = e.getMessage() != null ? e.getMessage() : "";
            
            if (rawMsg.contains("namespace") || rawMsg.contains("prefix") || rawMsg.contains("Entity")) {
                return null; 
            }

            int line = e.getLineNumber() > 0 ? e.getLineNumber() : 1;
            String msg = rawMsg.split(" \\(position:")[0].replace("expected: /", "XML Error: Unclosed tag or missing parent element.");
            errors.add(new Console.ErrorItem(filePath, line, 0, 1, "XML Syntax Error: " + msg, false, false));
        } catch (Exception e) {
            return null;
        }
        return errors;
    }
}
