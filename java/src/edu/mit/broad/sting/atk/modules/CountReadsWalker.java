package edu.mit.broad.sting.atk.modules;

import net.sf.samtools.SAMRecord;
import edu.mit.broad.sting.atk.LocusContext;

public class CountReadsWalker extends BasicReadWalker<Integer, Integer> {
    public Integer map(LocusContext context, SAMRecord read) {
        return 1;
    }

    public Integer reduceInit() { return 0; }

    public Integer reduce(Integer value, Integer sum) {
        return value + sum;
    }
}
