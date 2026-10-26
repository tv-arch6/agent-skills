package com.google.android.exoplayer2.text;
public final class Cue {
    public static final int LINE_TYPE_FRACTION = 0;
    public static final int ANCHOR_TYPE_START = 0;
    public static final class Builder {
        public Builder() { }
        public Builder setText(CharSequence text) { return this; }
        public Builder setLine(float line, int lineType) { return this; }
        public Builder setLineAnchor(int anchor) { return this; }
        public Cue build() { return new Cue(); }
    }
}
