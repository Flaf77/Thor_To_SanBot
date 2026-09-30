package com.thorbridge.sanbot.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.robot.RobotState;

import java.util.ArrayList;
import java.util.List;

/** Card that lists every entry of one RobotState group with its live value and age. */
public class GroupView {

    public final LinearLayout card;
    private final String group;
    private final LinearLayout rows;
    private final List<TextView[]> cells = new ArrayList<>();
    private List<RobotState.Entry> shown = new ArrayList<>();

    public GroupView(Context c, RobotState.Group g) {
        this(c, g.id, g.title);
    }

    public GroupView(Context c, String group, String title) {
        this.group = group;
        card = Ui.card(c, title);
        rows = Ui.vbox(c);
        card.addView(rows);
    }

    public void refresh() {
        Context c = card.getContext();
        List<RobotState.Entry> entries = App.get().state().entries(group);
        if (entries.size() != shown.size()) {
            rows.removeAllViews();
            cells.clear();
            if (entries.isEmpty()) {
                rows.addView(Ui.text(c, "No data yet - values appear here as soon as the robot reports them.", 13, Ui.DIM));
            }
            for (RobotState.Entry e : entries) {
                LinearLayout r = Ui.hbox(c);
                TextView label = Ui.text(c, e.label, 14, Ui.DIM);
                TextView value = Ui.text(c, "", 14, Ui.TEXT);
                TextView age = Ui.text(c, "", 12, Ui.DIM);
                r.addView(label, Ui.weight(1.1f));
                r.addView(value, Ui.weight(1.4f));
                r.addView(age, Ui.weight(0.4f));
                rows.addView(r);
                cells.add(new TextView[]{label, value, age});
            }
            shown = entries;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < shown.size(); i++) {
            RobotState.Entry e = shown.get(i);
            TextView[] t = cells.get(i);
            t[1].setText(e.text());
            if (e.raw == null) {
                t[1].setTextColor(Ui.DIM);
                t[2].setText("");
            } else {
                long age = now - e.updatedAt;
                t[1].setTextColor(age < 2000 ? Ui.OK : Ui.TEXT);
                t[2].setText(age < 1000 ? "now" : age < 60000 ? (age / 1000) + " s ago" : (age / 60000) + " min ago");
            }
        }
    }
}
