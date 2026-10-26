package com.google.android.exoplayer2.ui;
import com.google.android.exoplayer2.text.Cue;
import java.util.List;
public class SubtitleView extends android.view.View {
    public static final float DEFAULT_TEXT_SIZE_FRACTION = 0.0533f;
    public SubtitleView(android.content.Context c) { super(c); }
    public void setCues(List<Cue> cues) { }
    public void setFractionalTextSize(float fractionOfHeight) { }
    public void setBottomPaddingFraction(float bottomPaddingFraction) { }
}
