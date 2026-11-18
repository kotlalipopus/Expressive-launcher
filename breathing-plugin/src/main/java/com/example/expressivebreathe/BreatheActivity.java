package com.example.expressivebreathe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BreatheActivity extends Activity {
    private ValueAnimator animation;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 28, 43));
        getWindow().setNavigationBarColor(Color.rgb(20, 28, 43));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(24), dp(28), dp(24));
        content.setBackgroundColor(Color.rgb(20, 28, 43));

        TextView title = text(R.string.title, 30, Color.WHITE);
        content.addView(title);
        TextView hint = text(R.string.hint, 16, Color.rgb(188, 200, 220));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(12);
        content.addView(hint, hintParams);

        View circle = new View(this);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(150, 191, 255), Color.rgb(135, 113, 224)});
        shape.setShape(GradientDrawable.OVAL);
        circle.setBackground(shape);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(200), dp(200));
        circleParams.gravity = Gravity.CENTER;
        circleParams.topMargin = dp(80);
        circleParams.bottomMargin = dp(70);
        content.addView(circle, circleParams);

        TextView phase = text(R.string.inhale, 24, Color.WHITE);
        content.addView(phase);
        Button close = new Button(this);
        close.setText(R.string.close);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, -2);
        buttonParams.gravity = Gravity.CENTER;
        buttonParams.topMargin = dp(48);
        content.addView(close, buttonParams);
        setContentView(content);

        animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(4000);
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.setRepeatMode(ValueAnimator.REVERSE);
        animation.setInterpolator(new AccelerateDecelerateInterpolator());
        animation.addUpdateListener(animator -> {
            float fraction = (float) animator.getAnimatedValue();
            float scale = 0.7f + fraction * 0.3f;
            circle.setScaleX(scale);
            circle.setScaleY(scale);
            phase.setText((animator.getCurrentPlayTime() / 4000) % 2 == 0
                    ? R.string.inhale : R.string.exhale);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        animation.start();
    }

    @Override protected void onPause() {
        animation.cancel();
        super.onPause();
    }

    private TextView text(int resource, int size, int color) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
