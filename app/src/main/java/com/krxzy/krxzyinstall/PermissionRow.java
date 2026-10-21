package com.krxzy.krxzyinstall;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PermissionRow {
    private final View root;
    private final ImageView icon;
    private final TextView status;
    private final Button action;

    public PermissionRow(Context context, LinearLayout parent, String title,
                         String description, Runnable onAllow) {
        root = LayoutInflater.from(context).inflate(R.layout.item_permission, parent, false);
        parent.addView(root);
        ((TextView) root.findViewById(R.id.permTitle)).setText(title);
        ((TextView) root.findViewById(R.id.permDesc)).setText(description);
        icon = root.findViewById(R.id.permIcon);
        status = root.findViewById(R.id.permStatus);
        action = root.findViewById(R.id.permAction);
        action.setOnClickListener(view -> onAllow.run());
    }

    public void update(boolean granted) {
        if (granted) {
            icon.setImageResource(R.drawable.ic_check_24);
            Ui.chip(status, root.getContext().getString(R.string.granted),
                    R.drawable.bg_chip_good, root.getContext().getColor(R.color.accent));
            action.setVisibility(View.GONE);
            icon.setColorFilter(root.getContext().getColor(R.color.accent));
        } else {
            icon.setImageResource(R.drawable.ic_warn_24);
            Ui.chip(status, root.getContext().getString(R.string.needed),
                    R.drawable.bg_chip_warn, root.getContext().getColor(R.color.warn));
            action.setVisibility(View.VISIBLE);
            icon.setColorFilter(root.getContext().getColor(R.color.warn));
        }
    }
}
