// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;

import java.util.*;
import java.util.function.BiPredicate;

/** Pure policy over real counted items. It never mutates or confiscates an inventory. */
public final class Inspection {
    public static final long MAX_COUNT=1_000_000;
    public enum Outcome { CLEAR,MATCH,TOO_COMPLEX,EXEMPT }
    public record Match(String item,long count){}
    public record Result(Outcome outcome,List<Match> matches){
        public Result{matches=List.copyOf(matches);}
        public boolean accepted(){return outcome==Outcome.CLEAR||outcome==Outcome.EXEMPT;}
    }
    static final Result CLEAR=new Result(Outcome.CLEAR,List.of()),TOO_COMPLEX=new Result(Outcome.TOO_COMPLEX,List.of()),EXEMPT=new Result(Outcome.EXEMPT,List.of());
    public static Result evaluate(UUID player,Map<String,Long> counts,List<FilterRule> rules,BiPredicate<String,String> tagged){
        var blocked=new HashMap<String,Long>();
        for(var rule:rules){
            if(rule.bypasses(player))continue;
            String selector=rule.selector();
            if(selector.charAt(0)!='#'){
                long count=Math.max(0,counts.getOrDefault(selector,0L));
                if(count>=rule.minimum())blocked.put(selector,Math.min(MAX_COUNT,count));
                continue;
            }
            String tag=selector.substring(1);long total=0;List<Map.Entry<String,Long>> matches=null;
            for(var entry:counts.entrySet())if(entry.getValue()>0&&tagged.test(tag,entry.getKey())){
                total=Math.min(MAX_COUNT,total+entry.getValue());if(matches==null)matches=new ArrayList<>();matches.add(entry);
            }
            if(total<rule.minimum())continue;
            if(matches!=null)for(var entry:matches)blocked.put(entry.getKey(),Math.min(MAX_COUNT,entry.getValue()));
        }
        if(blocked.isEmpty())return CLEAR;
        var found=blocked.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(16).map(e->new Match(e.getKey(),e.getValue())).toList();
        return new Result(Outcome.MATCH,found);
    }
    private Inspection(){}
}
