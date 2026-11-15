package dev.mccomm.cell.comm;

/** Phone numbers are stored as 7 digits ("3124821") and shown as "312-4821". The first 3 digits are the carrier prefix. */
public final class Numbers {
    private Numbers() {
    }

    /** Digits only, or null if the input isn't a 7-digit number. */
    public static String normalize(String input) {
        if (input == null) return null;
        String d = input.replaceAll("[^0-9]", "");
        return d.length() == 7 ? d : null;
    }

    public static String format(String digits) {
        if (digits == null || digits.length() != 7) return digits == null ? "?" : digits;
        return digits.substring(0, 3) + "-" + digits.substring(3);
    }
}
