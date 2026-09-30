package com.thorbridge.sanbot.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** Tiny helpers to build the UI in code (dark theme, tablet sized). */
public final class Ui {

    public static final int BG = 0xFF101418;
    public static final int CARD = 0xFF1C232B;
    public static final int TEXT = 0xFFE8EDF2;
    public static final int DIM = 0xFF8A96A3;
    public static final int ACCENT = 0xFF3FA9F5;
    public static final int OK = 0xFF4CD07D;
    public static final int WARN = 0xFFF5B83F;
    public static final int BAD = 0xFFF0524F;

    public interface IntCallback {
        void onValue(int v);
    }

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView mono(Context c, String s, float sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** A rounded card with a title; add children to the returned layout. */
    public static LinearLayout card(Context c, String title) {
        LinearLayout l = vbox(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(c, 8));
        l.setBackground(bg);
        int p = dp(c, 10);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        l.setLayoutParams(lp);
        if (title != null) {
            TextView t = text(c, title, 17, ACCENT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(0, 0, 0, dp(c, 6));
            l.addView(t);
        }
        return l;
    }

    public static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(l);
        return b;
    }

    public static Button coloredButton(Context c, String label, int color, View.OnClickListener l) {
        Button b = button(c, label, l);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, 6));
        b.setBackground(bg);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    /** Label + SeekBar with a value in [min,max]; callback fires when the user releases the thumb. */
    public static LinearLayout slider(Context c, String label, final int min, int max, int value, final IntCallback onRelease) {
        LinearLayout row = hbox(c);
        final TextView t = text(c, label + ": " + value, 15, TEXT);
        t.setMinWidth(dp(c, 170));
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        final String base = label;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(base + ": " + (p + min));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (onRelease != null) onRelease.onValue(s.getProgress() + min);
            }
        });
        row.addView(t);
        row.addView(sb, weight(1));
        return row;
    }

    public static int sliderValue(LinearLayout slider, int min) {
        return ((SeekBar) slider.getChildAt(1)).getProgress() + min;
    }

    public static Spinner spinner(Context c, Object[] items) {
        Spinner s = new Spinner(c);
        ArrayAdapter<Object> a = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    public static EditText edit(Context c, String hint, String value, boolean numeric) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setSingleLine(true);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    public static CheckBox check(Context c, String label, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setChecked(checked);
        return cb;
    }

    public static int parseInt(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return def;
        }
    }
}
