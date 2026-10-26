package com.Cigram.vid;
// signatures copied from the project's CigramPlayerSheet.java (public API only)
public final class CigramPlayerSheet {
    public static final class Options {
        public Options(String[] labels, int selected) { }
        public Options(String[] labels, int selected, String hint) { }
    }
    public interface Source { Options load(); String pick(int which); }
    public interface Toggle { boolean toggle(); }
    public android.widget.TextView item(String name, String value, Source source) { return null; }
    public void toggle(String name, boolean on, Toggle toggle) { }
}
