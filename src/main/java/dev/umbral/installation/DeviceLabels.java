// SPDX-License-Identifier: MPL-2.0

package dev.umbral.installation;

import java.text.Normalizer;
import java.util.Locale;

/** Player-facing names stay plain text; search accepts accents and repeated spaces. */
public final class DeviceLabels {
    public static String clean(String input) {
        if(input==null)return "";
        String value=Normalizer.normalize(input,Normalizer.Form.NFC).strip();
        if(value.length()>48||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE||c=='§'))return "";
        return value.replaceAll("\\s+"," ");
    }
    public static String searchKey(String input) {
        return Normalizer.normalize(input==null?"":input,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ");
    }
    private DeviceLabels(){}
}
