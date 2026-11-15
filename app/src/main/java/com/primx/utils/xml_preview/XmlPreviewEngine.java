package com.primx.utils.xml_preview;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilderFactory;

public class XmlPreviewEngine {

    public static View inflate(Context context, File xmlFile, String xmlContent, File projectDir) {
        if (context == null) return null;
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            if (xmlFile != null && xmlFile.exists()) xmlContent = XmlResManager.readFileToString(xmlFile);
        }
        if (xmlContent == null || xmlContent.trim().isEmpty()) return createErrorView(context, "Empty Layout!");

        XmlResManager.CachedRes res = XmlResManager.getOrLoadResources(projectDir);

        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xmlContent)));
            Element root = doc.getDocumentElement();
            if (root == null) return createErrorView(context, "No root element.");

            XmlUtils.IdResolver idResolver = new XmlUtils.IdResolver();
            idResolver.collectIds(root);

            View rootView = inflateElement(context, root, null, res, projectDir, idResolver);
            if (rootView == null) return createErrorView(context, "Unsupported root layout.");

            if (rootView.getLayoutParams() == null) rootView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return rootView;
        } catch (Exception e) {
            return createErrorView(context, "XML Syntax Error:\n" + e.getMessage());
        }
    }

    private static View inflateElement(Context context, Element elem, ViewGroup parent, XmlResManager.CachedRes res, File projectDir, XmlUtils.IdResolver idResolver) {
        String tag = elem.getTagName();
        tag = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;

        View view = createViewForTag(context, tag);
        if (view == null) view = new LinearLayout(context);

        view.setTag(elem);
        view.setClickable(true);

        String idStr = XmlUtils.getAttr(elem, "id");
        if (!idStr.isEmpty()) view.setId(idResolver.getId(idStr));

        XmlAttrApplier.applyLayoutParams(context, view, elem, parent, idResolver);
        
        // 🔥 FIX: बैकग्राउंड ड्राएबल लोड करने के लिए projectDir पास किया गया है
        XmlAttrApplier.applyPaddingAndBackground(context, view, elem, res.colors, tag, projectDir);

        String vis = XmlUtils.getAttr(elem, "visibility").toLowerCase();
        if ("gone".equals(vis)) view.setVisibility(View.GONE);
        else if ("invisible".equals(vis)) view.setVisibility(View.INVISIBLE);

        if (view instanceof TextView) XmlAttrApplier.configureTextView(context, (TextView) view, elem, res.strings, res.colors);
        if (view instanceof ImageView) XmlAttrApplier.configureImageView(context, (ImageView) view, elem, projectDir);
        if (view instanceof ProgressBar && "true".equalsIgnoreCase(XmlUtils.getAttr(elem, "indeterminate"))) ((ProgressBar) view).setIndeterminate(true);

        if (view instanceof LinearLayout) {
            ((LinearLayout) view).setOrientation("horizontal".equalsIgnoreCase(XmlUtils.getAttr(elem, "orientation")) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            String ws = XmlUtils.getAttr(elem, "weightSum");
            if (!ws.isEmpty()) try { ((LinearLayout) view).setWeightSum(Float.parseFloat(ws)); } catch (Exception e){}
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            if (view instanceof ScrollView || view instanceof HorizontalScrollView) {
                LinearLayout scrollInner = new LinearLayout(context);
                scrollInner.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                scrollInner.setOrientation(view instanceof HorizontalScrollView ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                vg.addView(scrollInner);
                vg = scrollInner;
            }
            NodeList children = elem.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    View childView = inflateElement(context, (Element) children.item(i), vg, res, projectDir, idResolver);
                    if (childView != null) vg.addView(childView);
                }
            }
        }
        return view;
    }

    private static View createViewForTag(Context context, String tag) {
        if (tag.endsWith("LinearLayout")) return new LinearLayout(context);
        if (tag.endsWith("RelativeLayout") || tag.endsWith("ConstraintLayout")) return new RelativeLayout(context);
        if (tag.endsWith("FrameLayout") || tag.endsWith("CardView")) return new FrameLayout(context);
        if (tag.endsWith("ScrollView")) return new ScrollView(context);
        if (tag.endsWith("HorizontalScrollView")) return new HorizontalScrollView(context);
        if (tag.endsWith("TextView")) return new TextView(context);
        if (tag.endsWith("Button")) return new Button(context);
        if (tag.endsWith("EditText")) return new EditText(context);
        if (tag.endsWith("ImageView") || tag.endsWith("FloatingActionButton")) return new ImageView(context);
        if (tag.endsWith("ProgressBar")) return new ProgressBar(context);
        if (tag.endsWith("CheckBox")) return new CheckBox(context);
        if (tag.endsWith("RadioButton")) return new RadioButton(context);
        if (tag.endsWith("RadioGroup")) return new RadioGroup(context);
        if (tag.endsWith("Switch")) return new Switch(context);
        return new View(context);
    }

    private static View createErrorView(Context context, String error) {
        LinearLayout err = new LinearLayout(context);
        err.setOrientation(LinearLayout.VERTICAL);
        err.setGravity(Gravity.CENTER);
        err.setBackgroundColor(XmlUtils.bgColor(context));
        TextView tv = new TextView(context);
        tv.setText("Preview Error:\n" + error);
        tv.setTextColor(Color.parseColor("#FF5252"));
        err.addView(tv);
        return err;
    }
}
