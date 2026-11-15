package com.primx.ui.explorer;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.LinearLayout; // 🔥 नया Import

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FilesSearchManager {

    private final Filesview view;

    public FilesSearchManager(Filesview view) {
        this.view = view;
    }

    public void setupSearch(boolean isLight) {
        if (view.btnToggleSearch != null) {
            view.btnToggleSearch.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (view.searchContainer != null && view.inputSearch != null) {

							// headerContainer को ढूँढो (जिसमें Create New Project है)
							int hcId = view.getResources().getIdentifier("headerContainer", "id", view.getContext().getPackageName());
							LinearLayout headerContainer = null;
							if(hcId != 0) {
								headerContainer = view.findViewById(hcId);
							}

							if (view.searchContainer.getVisibility() == View.VISIBLE) {
								// सर्च बंद करो
								view.searchContainer.setVisibility(View.GONE);
								if(headerContainer != null) headerContainer.setVisibility(View.VISIBLE); // हेडर वापस लाओ

								view.inputSearch.setText("");
								view.initFileManager();
								hideKeyboard();
							} else {
								// सर्च चालू करो
								view.searchContainer.setVisibility(View.VISIBLE);
								if(headerContainer != null) headerContainer.setVisibility(View.GONE); // हेडर छुपाओ (ओवरलैप)

								view.inputSearch.requestFocus();
								showKeyboard(view.inputSearch);
							}
						}
					}
				});
        }

        if (view.inputSearch != null) {
            view.inputSearch.addTextChangedListener(new TextWatcher() {
					@Override
					public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

					@Override
					public void afterTextChanged(Editable s) {}

					@Override
					public void onTextChanged(CharSequence s, int start, int before, int count) {
						String query = s.toString().trim();
						if (query.isEmpty()) {
							view.initFileManager();
						} else {
							performSearch(query);
						}
					}
				});
        }
    }

    public void hideKeyboard() {
        if (view.inputSearch == null) return;
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.inputSearch.getWindowToken(), 0);
    }

    public void showKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    public void performSearch(final String query) {
        if (view.rootDir == null || !view.rootDir.exists() || view.fileTreeContainer == null) return;
        view.fileTreeContainer.removeAllViews();
        view.executorService.execute(new Runnable() {
				@Override
				public void run() {
					final List<File> matchedFiles = new ArrayList<>();
					searchRecursively(view.rootDir, query.toLowerCase(), matchedFiles, 50);
					view.post(new Runnable() {
							@Override
							public void run() {
								if (matchedFiles.isEmpty()) {
									TextView noRes = new TextView(view.getContext());
									noRes.setText("No matching files found");
									noRes.setPadding(30, 30, 30, 30);
									noRes.setTextColor(Color.parseColor("#FF888888"));
									view.fileTreeContainer.addView(noRes);
								} else {
									for (File f : matchedFiles) {
										view.fileTreeContainer.addView(view.treeStyle.createFileRow(f, 0, 0, view.fileTreeContainer));
									}
								}
							}
						});
				}
			});
    }

    public void searchRecursively(File dir, String query, List<File> results, int maxResults) {
        if (results.size() >= maxResults) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isHidden()) continue;
            if (f.getName().toLowerCase().contains(query)) {
                results.add(f);
                if (results.size() >= maxResults) return;
            }
            if (f.isDirectory()) searchRecursively(f, query, results, maxResults);
        }
    }
}

