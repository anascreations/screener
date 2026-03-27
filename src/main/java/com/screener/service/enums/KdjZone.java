package com.screener.service.enums;

public enum KdjZone {
	 
    BULLISH_STRONG ("Bullish Strong — K>D, J>50", "✅", "KDJ fully aligned upward — strong oscillator momentum"),
    BULLISH_WEAK   ("Bullish Weak — K>D, J≤50",  "🟢", "KDJ crossed bullish — momentum still building"),
    OVERSOLD       ("Oversold — J<20",             "💡", "J in extreme oversold zone — bounce potential, wait K>D confirm"),
    NEUTRAL        ("Neutral — K≈D",               "⚪", "KDJ undecided — wait for a clean directional cross"),
    OVERBOUGHT     ("Overbought — J>80",            "🟡", "J extremely high — risk of reversal, reduce size or wait"),
    BEARISH        ("Bearish — K<D",                "🔴", "KDJ pointing down — downward pressure, avoid entry"),
    UNKNOWN        ("Unknown — not provided",        "⚪", "KDJ values not provided");
 
    public final String label;
    public final String emoji;
    public final String meaning;
 
    KdjZone(String label, String emoji, String meaning) {
        this.label   = label;
        this.emoji   = emoji;
        this.meaning = meaning;
    }
}
 