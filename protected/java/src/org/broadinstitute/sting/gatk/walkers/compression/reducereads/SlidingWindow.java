package org.broadinstitute.sting.gatk.walkers.compression.reducereads;

import com.google.java.contract.Requires;
import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMFileHeader;
import org.broadinstitute.sting.gatk.downsampling.ReservoirDownsampler;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.recalibration.EventType;
import org.broadinstitute.sting.utils.sam.GATKSAMReadGroupRecord;
import org.broadinstitute.sting.utils.sam.GATKSAMRecord;
import org.broadinstitute.sting.utils.sam.ReadUtils;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: roger
 * Date: 8/3/11
 * Time: 2:24 PM
 */
public class SlidingWindow {

    // Sliding Window data
    final private LinkedList<GATKSAMRecord> readsInWindow;
    final private LinkedList<HeaderElement> windowHeader;
    protected int contextSize;                                                                                          // the largest context size (between mismatches and indels)
    protected String contig;
    protected int contigIndex;
    protected SAMFileHeader samHeader;
    protected GATKSAMReadGroupRecord readGroupAttribute;
    protected int downsampleCoverage;

    // Running consensus data
    protected SyntheticRead runningConsensus;
    protected int consensusCounter;
    protected String consensusReadName;

    // Filtered Data Consensus data
    protected SyntheticRead filteredDataConsensus;
    protected int filteredDataConsensusCounter;
    protected String filteredDataReadName;


    // Additional parameters
    protected double MIN_ALT_BASE_PROPORTION_TO_TRIGGER_VARIANT;                                                        // proportion has to be greater than this value to trigger variant region due to mismatches
    protected double MIN_INDEL_BASE_PROPORTION_TO_TRIGGER_VARIANT;                                                      // proportion has to be greater than this value to trigger variant region due to deletions
    protected int MIN_BASE_QUAL_TO_COUNT;                                                                               // qual has to be greater than or equal to this value
    protected int MIN_MAPPING_QUALITY;

    protected ReduceReads.DownsampleStrategy downsampleStrategy;
    private boolean hasIndelQualities;

    private final int nContigs;

    /**
     * The types of synthetic reads to use in the finalizeAndAdd method
     */
    private enum ConsensusType {
        CONSENSUS,
        FILTERED,
        BOTH
    }

    public int getStopLocation() {
        return getStopLocation(windowHeader);
    }

    private int getStopLocation(LinkedList<HeaderElement> header) {
        return getStartLocation(header) + header.size() - 1;
    }

    public String getContig() {
        return contig;
    }

    public int getContigIndex() {
        return contigIndex;
    }

    public int getStartLocation(LinkedList<HeaderElement> header) {
        return header.isEmpty() ? -1 : header.peek().getLocation();
    }


    public SlidingWindow(String contig, int contigIndex, int contextSize, SAMFileHeader samHeader, GATKSAMReadGroupRecord readGroupAttribute, int windowNumber, final double minAltProportionToTriggerVariant, final double minIndelProportionToTriggerVariant, int minBaseQual, int minMappingQuality, int downsampleCoverage, final ReduceReads.DownsampleStrategy downsampleStrategy, boolean hasIndelQualities, int nContigs) {
        this.contextSize = contextSize;
        this.downsampleCoverage = downsampleCoverage;

        this.MIN_ALT_BASE_PROPORTION_TO_TRIGGER_VARIANT = minAltProportionToTriggerVariant;
        this.MIN_INDEL_BASE_PROPORTION_TO_TRIGGER_VARIANT = minIndelProportionToTriggerVariant;
        this.MIN_BASE_QUAL_TO_COUNT = minBaseQual;
        this.MIN_MAPPING_QUALITY = minMappingQuality;

        this.windowHeader = new LinkedList<HeaderElement>();
        this.readsInWindow = new LinkedList<GATKSAMRecord>();

        this.contig = contig;
        this.contigIndex = contigIndex;
        this.samHeader = samHeader;
        this.readGroupAttribute = readGroupAttribute;

        this.consensusCounter = 0;
        this.consensusReadName = "Consensus-" + windowNumber + "-";

        this.filteredDataConsensusCounter = 0;
        this.filteredDataReadName = "Filtered-" + windowNumber + "-";

        this.runningConsensus = null;
        this.filteredDataConsensus = null;
        
        this.downsampleStrategy = downsampleStrategy;
        this.hasIndelQualities = hasIndelQualities;
        this.nContigs = nContigs;
    }

    /**
     * Add a read to the sliding window and slides the window accordingly.
     * 
     * Reads are assumed to be in order, therefore, when a read is added the sliding window can
     * assume that no more reads will affect read.getUnclippedStart() - contextSizeMismatches. The window
     * slides forward to that position and returns all reads that may have been finalized in the
     * sliding process.
     *
     * @param read the read
     * @return a list of reads that have been finished by sliding the window.
     */
    public List<GATKSAMRecord> addRead(GATKSAMRecord read) {
        addToHeader(windowHeader, read);                                                                                // update the window header counts
        readsInWindow.add(read);                                                                                        // add read to sliding reads
        return slideWindow(read.getUnclippedStart());
    }

    /**
     * returns the next complete or incomplete variant region between 'from' (inclusive) and 'to' (exclusive)
     *
     * @param from         beginning window header index of the search window (inclusive)
     * @param to           end window header index of the search window (exclusive)
     * @param variantSite  boolean array with true marking variant regions
     * @return null if nothing is variant, start/stop if there is a complete variant region, start/-1 if there is an incomplete variant region.
     */
    private Pair<Integer, Integer> getNextVariantRegion(int from, int to, boolean[] variantSite) {
        boolean foundStart = false;
        int variantRegionStartIndex = 0;
        for (int i=from; i<to; i++) {
            if (variantSite[i] && !foundStart) {
                variantRegionStartIndex = i;
                foundStart = true;
            }
            else if(!variantSite[i] && foundStart) {
                return(new Pair<Integer, Integer>(variantRegionStartIndex, i-1));
            }
        }
        return (foundStart) ? new Pair<Integer, Integer>(variantRegionStartIndex, -1) : null;
    }

    /**
     * Creates a list with all the complete and incomplete variant regions within 'from' (inclusive) and 'to' (exclusive)
     *
     * @param from         beginning window header index of the search window (inclusive)
     * @param to           end window header index of the search window (exclusive)
     * @param variantSite  boolean array with true marking variant regions
     * @return a list with start/stops of variant regions following getNextVariantRegion description
     */
    private List<Pair<Integer, Integer>> getAllVariantRegions(int from, int to, boolean[] variantSite) {
        List<Pair<Integer,Integer>> regions = new LinkedList<Pair<Integer, Integer>>();
        int index = from;
        while(index < to) {
            Pair<Integer,Integer> result = getNextVariantRegion(index, to, variantSite);
            if (result == null)
                break;

            regions.add(result);
            if (result.getSecond() < 0)
                break;
            index = result.getSecond() + 1;
        }
        return regions;
    }


    /**
     * Determines if the window can be slid given the new incoming read.
     *
     * We check from the start of the window to the (unclipped) start of the new incoming read if there
     * is any variant.
     * If there are variant sites, we check if it's time to close the variant region.
     *
     * @param incomingReadUnclippedStart the incoming read's start position. Must be the unclipped start!
     * @return all reads that have fallen to the left of the sliding window after the slide
     */
    protected List<GATKSAMRecord> slideWindow(int incomingReadUnclippedStart) {
        List<GATKSAMRecord> finalizedReads = new LinkedList<GATKSAMRecord>();

        if (incomingReadUnclippedStart - contextSize > getStartLocation(windowHeader)) {
            int readStartHeaderIndex = incomingReadUnclippedStart - getStartLocation(windowHeader);
            boolean[] variantSite = markSites(getStartLocation(windowHeader) + readStartHeaderIndex);
            int breakpoint = Math.max(readStartHeaderIndex - contextSize - 1, 0);                                       // this is the limit of what we can close/send to consensus (non-inclusive)

            List<Pair<Integer,Integer>> regions = getAllVariantRegions(0, breakpoint, variantSite);
            finalizedReads = closeVariantRegions(regions, false);

            List<GATKSAMRecord> readsToRemove = new LinkedList<GATKSAMRecord>();
            for (GATKSAMRecord read : readsInWindow) {                                                                  // todo -- unnecessarily going through all reads in the window !! Optimize this (But remember reads are not sorted by alignment end!)
                if (read.getSoftEnd() < getStartLocation(windowHeader)) {
                    readsToRemove.add(read);
                }
            }
            for (GATKSAMRecord read : readsToRemove) {
                readsInWindow.remove(read);
            }
        }

        return finalizedReads;
    }

    /**
     * returns an array marked with variant and non-variant regions (it uses
     * markVariantRegions to make the marks)
     *
     * @param stop check the window from start to stop (not-inclusive)
     * @return a boolean array with 'true' marking variant regions and false marking consensus sites
     */
    protected boolean[] markSites(int stop) {

        boolean[] markedSites = new boolean[stop - getStartLocation(windowHeader) + contextSize + 1];

        Iterator<HeaderElement> headerElementIterator = windowHeader.iterator();
        for (int i = getStartLocation(windowHeader); i < stop; i++) {
            if (headerElementIterator.hasNext()) {
                HeaderElement headerElement = headerElementIterator.next();

                if (headerElement.isVariant(MIN_ALT_BASE_PROPORTION_TO_TRIGGER_VARIANT, MIN_INDEL_BASE_PROPORTION_TO_TRIGGER_VARIANT))
                    markVariantRegion(markedSites, i - getStartLocation(windowHeader));

            } else
                break;
        }
        return markedSites;
    }

    /**
     * Marks the sites around the variant site (as true)
     *
     * @param markedSites         the boolean array to bear the marks
     * @param variantSiteLocation the location where a variant site was found
     */
    protected void markVariantRegion(boolean[] markedSites, int variantSiteLocation) {
        int from = (variantSiteLocation < contextSize) ? 0 : variantSiteLocation - contextSize;
        int to = (variantSiteLocation + contextSize + 1 > markedSites.length) ? markedSites.length : variantSiteLocation + contextSize + 1;
        for (int i = from; i < to; i++)
            markedSites[i] = true;
    }

    /**
     * Adds bases to the running consensus or filtered data accordingly
     * 
     * If adding a sequence with gaps, it will finalize multiple consensus reads and keep the last running consensus
     *
     * @param start the first header index to add to consensus
     * @param end   the first header index NOT TO add to consensus
     * @return a list of consensus reads generated by this call. Empty list if no consensus was generated.
     */
    protected List<GATKSAMRecord> addToSyntheticReads(LinkedList<HeaderElement> header, int start, int end, boolean isNegativeStrand) {
        LinkedList<GATKSAMRecord> reads = new LinkedList<GATKSAMRecord>();
        if (start < end) {
            ListIterator<HeaderElement> headerElementIterator = header.listIterator(start);

            if (!headerElementIterator.hasNext())
                throw new ReviewedStingException(String.format("Requested to add to synthetic reads a region that contains no header element at index: %d  - %d / %d", start, header.size(), end));

            HeaderElement headerElement = headerElementIterator.next();

            if (headerElement.hasConsensusData()) {
                reads.addAll(finalizeAndAdd(ConsensusType.FILTERED));

                int endOfConsensus = findNextNonConsensusElement(header, start, end);
                addToRunningConsensus(header, start, endOfConsensus, isNegativeStrand);

                if (endOfConsensus <= start)
                    throw new ReviewedStingException(String.format("next start is <= current start: (%d <= %d)", endOfConsensus, start));

                reads.addAll(addToSyntheticReads(header, endOfConsensus, end, isNegativeStrand));
            } else if (headerElement.hasFilteredData()) {
                reads.addAll(finalizeAndAdd(ConsensusType.CONSENSUS));

                int endOfFilteredData = findNextNonFilteredDataElement(header, start, end);
                addToFilteredData(header, start, endOfFilteredData, isNegativeStrand);

                if (endOfFilteredData <= start)
                    throw new ReviewedStingException(String.format("next start is <= current start: (%d <= %d)", endOfFilteredData, start));

                reads.addAll(addToSyntheticReads(header, endOfFilteredData, end, isNegativeStrand));
            } else if (headerElement.isEmpty()) {
                reads.addAll(finalizeAndAdd(ConsensusType.BOTH));

                int endOfEmptyData = findNextNonEmptyElement(header, start, end);

                if (endOfEmptyData <= start)
                    throw new ReviewedStingException(String.format("next start is <= current start: (%d <= %d)", endOfEmptyData, start));

                reads.addAll(addToSyntheticReads(header, endOfEmptyData, end, isNegativeStrand));
            } else
                throw new ReviewedStingException(String.format("Header Element %d is neither Consensus, Data or Empty. Something is wrong.", start));

        }

        return reads;
    }

    /**
     * Finalizes one or more synthetic reads.
     *
     * @param type the synthetic reads you want to close
     * @return the GATKSAMRecords generated by finalizing the synthetic reads
     */
    private List<GATKSAMRecord> finalizeAndAdd(ConsensusType type) {
        GATKSAMRecord read = null;
        List<GATKSAMRecord> list = new LinkedList<GATKSAMRecord>();

        switch (type) {
            case CONSENSUS:
                read = finalizeRunningConsensus();
                break;
            case FILTERED:
                read = finalizeFilteredDataConsensus();
                break;
            case BOTH:
                read = finalizeRunningConsensus();
                if (read != null) list.add(read);
                read = finalizeFilteredDataConsensus();
        }
        if (read != null)
            list.add(read);

        return list;
    }

    /**
     * Looks for the next position without consensus data
     *
     * @param start beginning of the filtered region
     * @param upTo  limit to search for another consensus element
     * @return next position with consensus data or empty
     */
    private int findNextNonConsensusElement(LinkedList<HeaderElement> header, int start, int upTo) {
        Iterator<HeaderElement> headerElementIterator = header.listIterator(start);
        int index = start;
        while (index < upTo) {
            if (!headerElementIterator.hasNext())
                throw new ReviewedStingException("There are no more header elements in this window");

            HeaderElement headerElement = headerElementIterator.next();
            if (!headerElement.hasConsensusData())
                break;
            index++;
        }
        return index;
    }

    /**
     * Looks for the next position without filtered data
     *
     * @param start beginning of the region
     * @param upTo  limit to search for
     * @return next position with no filtered data
     */
    private int findNextNonFilteredDataElement(LinkedList<HeaderElement> header, int start, int upTo) {
        Iterator<HeaderElement> headerElementIterator = header.listIterator(start);
        int index = start;
        while (index < upTo) {
            if (!headerElementIterator.hasNext())
                throw new ReviewedStingException("There are no more header elements in this window");

            HeaderElement headerElement = headerElementIterator.next();
            if (!headerElement.hasFilteredData() || headerElement.hasConsensusData())
                break;
            index++;
        }
        return index;
    }

    /**
     * Looks for the next non-empty header element
     *
     * @param start beginning of the region
     * @param upTo  limit to search for
     * @return next position with non-empty element
     */
    private int findNextNonEmptyElement(LinkedList<HeaderElement> header, int start, int upTo) {
        ListIterator<HeaderElement> headerElementIterator = header.listIterator(start);
        int index = start;
        while (index < upTo) {
            if (!headerElementIterator.hasNext())
                throw new ReviewedStingException("There are no more header elements in this window");

            HeaderElement headerElement = headerElementIterator.next();
            if (!headerElement.isEmpty())
                break;
            index++;
        }
        return index;
    }


    /**
     * Adds bases to the filtered data synthetic read.
     * 
     * Different from the addToConsensus method, this method assumes a contiguous sequence of filteredData
     * bases.
     *
     * @param start the first header index to add to consensus
     * @param end   the first header index NOT TO add to consensus
     */
    private void addToFilteredData(LinkedList<HeaderElement> header, int start, int end, boolean isNegativeStrand) {
        if (filteredDataConsensus == null)
            filteredDataConsensus = new SyntheticRead(samHeader, readGroupAttribute, contig, contigIndex, filteredDataReadName + filteredDataConsensusCounter++, header.get(start).getLocation(), GATKSAMRecord.REDUCED_READ_CONSENSUS_TAG, hasIndelQualities, isNegativeStrand);

        ListIterator<HeaderElement> headerElementIterator = header.listIterator(start);
        for (int index = start; index < end; index++) {
            if (!headerElementIterator.hasNext())
                throw new ReviewedStingException("Requested to create a filtered data synthetic read from " + start + " to " + end + " but " + index + " does not exist");

            HeaderElement headerElement = headerElementIterator.next();
            if (headerElement.hasConsensusData())
                throw new ReviewedStingException("Found consensus data inside region to add to filtered data.");

            if (!headerElement.hasFilteredData())
                throw new ReviewedStingException("No filtered data in " + index);

            genericAddBaseToConsensus(filteredDataConsensus, headerElement.getFilteredBaseCounts(), headerElement.getRMS());
        }
    }

    /**
     * Adds bases to the filtered data synthetic read.
     * 
     * Different from the addToConsensus method, this method assumes a contiguous sequence of filteredData
     * bases.
     *
     * @param start the first header index to add to consensus
     * @param end   the first header index NOT TO add to consensus
     */
    private void addToRunningConsensus(LinkedList<HeaderElement> header, int start, int end, boolean isNegativeStrand) {
        if (runningConsensus == null)
            runningConsensus = new SyntheticRead(samHeader, readGroupAttribute, contig, contigIndex, consensusReadName + consensusCounter++, header.get(start).getLocation(), GATKSAMRecord.REDUCED_READ_CONSENSUS_TAG, hasIndelQualities, isNegativeStrand);

        Iterator<HeaderElement> headerElementIterator = header.listIterator(start);
        for (int index = start; index < end; index++) {
            if (!headerElementIterator.hasNext())
                throw new ReviewedStingException("Requested to create a running consensus synthetic read from " + start + " to " + end + " but " + index + " does not exist");

            HeaderElement headerElement = headerElementIterator.next();
            if (!headerElement.hasConsensusData())
                throw new ReviewedStingException("No CONSENSUS data in " + index);

            genericAddBaseToConsensus(runningConsensus, headerElement.getConsensusBaseCounts(), headerElement.getRMS());
        }
    }

    /**
     * Generic accessor to add base and qualities to a synthetic read
     *
     * @param syntheticRead the synthetic read to add to
     * @param baseCounts    the base counts object in the header element
     * @param rms           the rms mapping quality in the header element
     */
    private void genericAddBaseToConsensus(SyntheticRead syntheticRead, BaseAndQualsCounts baseCounts, double rms) {
        BaseIndex base = baseCounts.baseIndexWithMostCounts();
        byte count = (byte) Math.min(baseCounts.countOfMostCommonBase(), Byte.MAX_VALUE);
        byte qual = baseCounts.averageQualsOfMostCommonBase();
        byte insQual = baseCounts.averageInsertionQualsOfMostCommonBase();
        byte delQual = baseCounts.averageDeletionQualsOfMostCommonBase();
        syntheticRead.add(base, count, qual, insQual, delQual, rms);
    }

    private List<GATKSAMRecord> compressVariantRegion(int start, int stop) {
        List<GATKSAMRecord> allReads = new LinkedList<GATKSAMRecord>();

        // Try to compress into a polyploid consensus
        int nHaplotypes = 0;
        int hetRefPosition = -1;
        boolean canCompress = true;
        boolean foundEvent = false;
        Object[] header = windowHeader.toArray();
        for (int i = start; i<=stop; i++) {
            nHaplotypes = ((HeaderElement) header[i]).getNumberOfHaplotypes(MIN_ALT_BASE_PROPORTION_TO_TRIGGER_VARIANT);
            if (nHaplotypes > nContigs) {
                canCompress = false;
                break;
            }

            // guarantees that there is only 1 site in the variant region that needs more than one haplotype
            if (nHaplotypes > 1) {
                if (!foundEvent) {
                    foundEvent = true;
                    hetRefPosition = i;
                }
                else {
                    canCompress = false;
                    break;
                }
            }
        }

        int refStart = windowHeader.get(start).getLocation();
        int refStop = windowHeader.get(stop).getLocation();

        // Try to compress the variant region
        // the "foundEvent" protects us from trying to compress variant regions that are created by insertions
        if (canCompress && foundEvent) {
            allReads = createPolyploidConsensus(start, stop, nHaplotypes, ((HeaderElement) header[hetRefPosition]).getLocation());
        }

        // Return all reads that overlap the variant region and remove them from the window header entirely
        // also remove all reads preceding the variant region (since they will be output as consensus right after compression
        else {
            LinkedList<GATKSAMRecord> toRemove = new LinkedList<GATKSAMRecord>();
            for (GATKSAMRecord read : readsInWindow) {
                if (read.getSoftStart() <= refStop) {
                    if (read.getAlignmentEnd() >= refStart) {
                        allReads.add(read);
                        removeFromHeader(windowHeader, read);
                    }
                    toRemove.add(read);
                }
            }
            for (GATKSAMRecord read : toRemove) {
                readsInWindow.remove(read);
            }
        }
        return allReads;
    }

    /**
     * Finalizes a variant region, any adjacent synthetic reads.
     *
     * @param start the first window header index in the variant region (inclusive)
     * @param stop  the last window header index of the variant region (inclusive)
     * @return all reads contained in the variant region plus any adjacent synthetic reads
     */
    @Requires("start <= stop")
    protected List<GATKSAMRecord> closeVariantRegion(int start, int stop) {
        List<GATKSAMRecord> allReads = compressVariantRegion(start, stop);

        List<GATKSAMRecord> result = (downsampleCoverage > 0) ? downsampleVariantRegion(allReads) : allReads;
        result.addAll(addToSyntheticReads(windowHeader, 0, stop, false));
        result.addAll(finalizeAndAdd(ConsensusType.BOTH));

        return result;                                                                                                  // finalized reads will be downsampled if necessary
    }


    private List<GATKSAMRecord> closeVariantRegions(List<Pair<Integer, Integer>> regions, boolean forceClose) {
        List<GATKSAMRecord> allReads = new LinkedList<GATKSAMRecord>();
        if (!regions.isEmpty()) {
            int lastStop = -1;
            for (Pair<Integer, Integer> region : regions) {
                int start = region.getFirst();
                int stop = region.getSecond();
                if (stop < 0 && forceClose)
                    stop = windowHeader.size() - 1;
                if (stop >= 0) {
                    allReads.addAll(closeVariantRegion(start, stop));
                    lastStop = stop;
                }
            }
            for (int i = 0; i < lastStop; i++)                                                                          // clean up the window header elements up until the end of the variant region. (we keep the last element in case the following element had a read that started with insertion)
                windowHeader.remove();                                                                                  // todo -- can't believe java doesn't allow me to just do windowHeader = windowHeader.get(stop). Should be more efficient here!
        }
        return allReads;
    }

    /**
     * Downsamples a variant region to the downsample coverage of the sliding window.
     *
     * It will use the downsampling strategy defined by the SlidingWindow
     *
     * @param allReads the reads to select from (all reads that cover the window)
     * @return a list of reads selected by the downsampler to cover the window to at least the desired coverage
     */
    protected List<GATKSAMRecord> downsampleVariantRegion(final List<GATKSAMRecord> allReads) {
        int nReads = allReads.size();
        if (nReads == 0)
            return allReads;

        if (downsampleCoverage >= nReads)
            return allReads;

        ReservoirDownsampler <GATKSAMRecord> downsampler = new ReservoirDownsampler<GATKSAMRecord>(downsampleCoverage);
        downsampler.submit(allReads);
        return downsampler.consumeFinalizedItems();
    }


    /**
     * Properly closes a Sliding Window, finalizing all consensus and variant
     * regions that still exist regardless of being able to fulfill the
     * context size requirement in the end.
     *
     * @return All reads generated
     */
    public List<GATKSAMRecord> close() {
        // mark variant regions
        List<GATKSAMRecord> finalizedReads = new LinkedList<GATKSAMRecord>();

        if (!windowHeader.isEmpty()) {
            boolean[] variantSite = markSites(getStopLocation(windowHeader) + 1);
            List<Pair<Integer,Integer>> regions = getAllVariantRegions(0, windowHeader.size(), variantSite);
            finalizedReads = closeVariantRegions(regions, true);

            if (!windowHeader.isEmpty()) {
                finalizedReads.addAll(addToSyntheticReads(windowHeader, 0, windowHeader.size(), false));
                finalizedReads.addAll(finalizeAndAdd(ConsensusType.BOTH));                                              // if it ended in running consensus, finish it up
            }

        }
        return finalizedReads;
    }

    /**
     * generates the SAM record for the running consensus read and resets it (to null)
     *
     * @return the read contained in the running consensus
     */
    protected GATKSAMRecord finalizeRunningConsensus() {
        GATKSAMRecord finalizedRead = null;
        if (runningConsensus != null) {
            if (runningConsensus.size() > 0)
                finalizedRead = runningConsensus.close();
            else
                consensusCounter--;

            runningConsensus = null;
        }
        return finalizedRead;
    }

    /**
     * generates the SAM record for the filtered data consensus and resets it (to null)
     *
     * @return the read contained in the running consensus
     */
    protected GATKSAMRecord finalizeFilteredDataConsensus() {
        GATKSAMRecord finalizedRead = null;
        if (filteredDataConsensus != null) {
            if (filteredDataConsensus.size() > 0)
                finalizedRead = filteredDataConsensus.close();
            else
                filteredDataConsensusCounter--;

            filteredDataConsensus = null;
        }
        return finalizedRead;
    }



    private List<GATKSAMRecord> createPolyploidConsensus(int start, int stop, int nHaplotypes, int hetRefPosition) {
        // we will create two (positive strand, negative strand) headers for each contig
        List<LinkedList<HeaderElement>> headersPosStrand = new ArrayList<LinkedList<HeaderElement>>();
        List<LinkedList<HeaderElement>> headersNegStrand = new ArrayList<LinkedList<HeaderElement>>();
        List<GATKSAMRecord> hetReads = new LinkedList<GATKSAMRecord>();
        Map<Byte, Integer> haplotypeHeaderMap = new HashMap<Byte, Integer>(nHaplotypes);
        int currentHaplotype = 0;
        int refStart = windowHeader.get(start).getLocation();
        int refStop = windowHeader.get(stop).getLocation();
        List<GATKSAMRecord> toRemove = new LinkedList<GATKSAMRecord>();
        for (GATKSAMRecord read : readsInWindow) {
            int haplotype;

            // check if the read is either before or inside the variant region
            if (read.getSoftStart() <= refStop) {
                // check if the read is inside the variant region
                if (read.getMappingQuality() >= MIN_MAPPING_QUALITY && read.getSoftEnd() >= refStart) {
                    // check if the read contains the het site
                    if (read.getSoftStart() <= hetRefPosition && read.getSoftEnd() >= hetRefPosition) {
                        int readPos = ReadUtils.getReadCoordinateForReferenceCoordinate(read, hetRefPosition, ReadUtils.ClippingTail.LEFT_TAIL);
                        byte base = read.getReadBases()[readPos];
                        byte qual = read.getBaseQualities(EventType.BASE_SUBSTITUTION)[readPos];

                        // check if base passes the filters!
                        if (qual >= MIN_BASE_QUAL_TO_COUNT) {
                            // check which haplotype this read represents and take the index of it from the list of headers
                            if (haplotypeHeaderMap.containsKey(base)) {
                                haplotype = haplotypeHeaderMap.get(base);
                            }
                            // create new lists if this haplotype has not been seen yet
                            else {
                                haplotype = currentHaplotype;
                                haplotypeHeaderMap.put(base, currentHaplotype);
                                headersPosStrand.add(new LinkedList<HeaderElement>());
                                headersNegStrand.add(new LinkedList<HeaderElement>());
                                currentHaplotype++;
                            }
                            LinkedList<HeaderElement> header = read.getReadNegativeStrandFlag() ? headersNegStrand.get(haplotype) : headersPosStrand.get(haplotype);
                            // add to the polyploid header
                            addToHeader(header, read);
                            // remove from the standard header so that we don't double count it
                            removeFromHeader(windowHeader, read);
                        }
                    }
                }

                // we remove all reads before and inside the variant region from the window
                toRemove.add(read);
            }
        }

        for (LinkedList<HeaderElement> header : headersPosStrand) {
            if (header.size() > 0)
                hetReads.addAll(addToSyntheticReads(header, 0, header.size(), false));
            if (runningConsensus != null)
                hetReads.add(finalizeRunningConsensus());
        }
        for (LinkedList<HeaderElement> header : headersNegStrand) {
            if (header.size() > 0)
                hetReads.addAll(addToSyntheticReads(header, 0, header.size(), true));
            if (runningConsensus != null)
                hetReads.add(finalizeRunningConsensus());
        }

        for (GATKSAMRecord read : toRemove) {
            readsInWindow.remove(read);
        }
        return hetReads;
    }


    private void addToHeader(LinkedList<HeaderElement> header, GATKSAMRecord read) {
        updateHeaderCounts(header, read, false);
    }

    private void removeFromHeader(LinkedList<HeaderElement> header, GATKSAMRecord read) {
        updateHeaderCounts(header, read, true);
    }


    /**
     * Updates the sliding window's header counts with the incoming read bases, insertions
     * and deletions.
     *
     * @param header the sliding window header to use
     * @param read the incoming read to be added to the sliding window
     * @param removeRead if we are removing the read from the header or adding
     */
    private void updateHeaderCounts(LinkedList<HeaderElement> header, GATKSAMRecord read, boolean removeRead) {
        byte[] bases = read.getReadBases();
        byte[] quals = read.getBaseQualities();
        byte[] insQuals = read.getExistingBaseInsertionQualities();
        byte[] delQuals = read.getExistingBaseDeletionQualities();
        int readStart = read.getSoftStart();
        int readEnd = read.getSoftEnd();
        Cigar cigar = read.getCigar();

        int readBaseIndex = 0;
        int startLocation = getStartLocation(header);
        int locationIndex = startLocation < 0 ? 0 : readStart - startLocation;
        int stopLocation = getStopLocation(header);

        if (removeRead && locationIndex < 0)
            throw new ReviewedStingException("read is behind the Sliding Window. read: " + read + " start " + read.getUnclippedStart() + "," + read.getUnclippedEnd() + " cigar: " + read.getCigarString() + " window: " + startLocation + "," + stopLocation);

        if (!removeRead) {                                                                                              // we only need to create new header elements if we are adding the read, not when we're removing it
            if (locationIndex < 0) {                                                                                    // Do we need to add extra elements before the start of the header? -- this may happen if the previous read was clipped and this alignment starts before the beginning of the window
                for (int i = 1; i <= -locationIndex; i++)
                    header.addFirst(new HeaderElement(startLocation - i));

                startLocation = readStart;                                                               // update start location accordingly
                locationIndex = 0;
            }

            if (stopLocation < readEnd) {                                                                // Do we need to add extra elements to the header?
                int elementsToAdd = (stopLocation < 0) ? readEnd - readStart + 1 : readEnd - stopLocation;
                while (elementsToAdd-- > 0)
                    header.addLast(new HeaderElement(readEnd - elementsToAdd));
            }

            // Special case for leading insertions before the beginning of the sliding read
            if (ReadUtils.readStartsWithInsertion(read).getFirst() && (readStart == startLocation || startLocation < 0)) {
                header.addFirst(new HeaderElement(readStart - 1));                                 // create a new first element to the window header with no bases added
                locationIndex = 1;                                                                                      // This allows the first element (I) to look at locationIndex - 1 in the subsequent switch and do the right thing.
            }
        }

        Iterator<HeaderElement> headerElementIterator = header.listIterator(locationIndex);
        HeaderElement headerElement;
        for (CigarElement cigarElement : cigar.getCigarElements()) {
            switch (cigarElement.getOperator()) {
                case H:
                    break;
                case I:
                    if (removeRead && locationIndex == 0) {                                                             // special case, if we are removing a read that starts in insertion and we don't have the previous header element anymore, don't worry about it.
                        break;
                    }

                    headerElement = header.get(locationIndex - 1);                                                // insertions are added to the base to the left (previous element)

                    if (removeRead) {
                        headerElement.removeInsertionToTheRight();
                    }
                    else {
                        headerElement.addInsertionToTheRight();
                    }
                    readBaseIndex += cigarElement.getLength();
                    break;                                                                                              // just ignore the insertions at the beginning of the read
                case D:
                    int nDeletions = cigarElement.getLength();
                    while (nDeletions-- > 0) {                                                                          // deletions are added to the baseCounts with the read mapping quality as it's quality score
                        headerElement = headerElementIterator.next();
                        byte mq = (byte) read.getMappingQuality();
                        if (removeRead)
                            headerElement.removeBase((byte) 'D', mq, mq, mq, mq, MIN_BASE_QUAL_TO_COUNT, MIN_MAPPING_QUALITY, false);
                        else
                            headerElement.addBase((byte) 'D', mq, mq, mq, mq, MIN_BASE_QUAL_TO_COUNT, MIN_MAPPING_QUALITY, false);

                        locationIndex++;
                    }
                    break;
                case S:
                case M:
                case P:
                case EQ:
                case X:
                    int nBasesToAdd = cigarElement.getLength();
                    while (nBasesToAdd-- > 0) {
                        headerElement = headerElementIterator.next();
                        byte insertionQuality = insQuals == null ? -1 : insQuals[readBaseIndex];                        // if the read doesn't have indel qualities, use -1 (doesn't matter the value because it won't be used for anything)
                        byte deletionQuality = delQuals == null ? -1 : delQuals[readBaseIndex];
                        if (removeRead)
                            headerElement.removeBase(bases[readBaseIndex], quals[readBaseIndex], insertionQuality, deletionQuality, read.getMappingQuality(), MIN_BASE_QUAL_TO_COUNT, MIN_MAPPING_QUALITY, cigarElement.getOperator() == CigarOperator.S);
                        else
                            headerElement.addBase(bases[readBaseIndex], quals[readBaseIndex], insertionQuality, deletionQuality, read.getMappingQuality(), MIN_BASE_QUAL_TO_COUNT, MIN_MAPPING_QUALITY, cigarElement.getOperator() == CigarOperator.S);

                        readBaseIndex++;
                        locationIndex++;
                    }
                    break;
            }
        }
    }
}

