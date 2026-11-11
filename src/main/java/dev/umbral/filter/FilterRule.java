// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import java.util.*;
import java.util.regex.Pattern;

/** An item/tag selector and per-rule exceptions. Names always come from server profiles. */
public record FilterRule(String selector,int minimum,List<Exemption> exemptions) {
    public static final int MAX_RULES=16,MAX_EXEMPTIONS=16;
    private static final Pattern SELECTOR=Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Exemption(UUID id,String name){
        public static final Codec<Exemption> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.fieldOf("id").forGetter(Exemption::id),Codec.STRING.fieldOf("name").forGetter(Exemption::name)).apply(i,Exemption::new));
        public Exemption{name=name.substring(0,Math.min(32,name.length()));}
    }
    public static final Codec<FilterRule> CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("selector").forGetter(FilterRule::selector),Codec.intRange(1,4096).fieldOf("minimum").forGetter(FilterRule::minimum),Exemption.CODEC.listOf(0,MAX_EXEMPTIONS).optionalFieldOf("exemptions",List.of()).forGetter(FilterRule::exemptions)).apply(i,FilterRule::new));
    public FilterRule {
        if(!validSelector(selector)||minimum<1||minimum>4096||exemptions.size()>MAX_EXEMPTIONS)throw new IllegalArgumentException("Invalid inspection rule");
        exemptions=List.copyOf(exemptions);if(exemptions.stream().map(Exemption::id).distinct().count()!=exemptions.size())throw new IllegalArgumentException("Duplicate exemption");
    }
    public FilterRule(String selector,int minimum){this(selector,minimum,List.of());}
    public static boolean validSelector(String selector){return selector!=null&&selector.length()<=128&&SELECTOR.matcher(selector).matches();}
    public boolean bypasses(UUID id){return exemptions.stream().anyMatch(e->e.id.equals(id));}
}
