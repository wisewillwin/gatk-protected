package org.broadinstitute.sting.gatk.refdata;

import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.refdata.tracks.RMDTrack;
import org.broadinstitute.sting.gatk.refdata.tracks.RMDTrackCreationException;
import org.broadinstitute.sting.gatk.refdata.tracks.RODRMDTrack;
import org.broadinstitute.sting.gatk.refdata.tracks.builders.RMDTrackBuilder;
import org.broadinstitute.sting.gatk.refdata.utils.LocationAwareSeekableRODIterator;
import org.broadinstitute.sting.gatk.refdata.utils.RODRecordList;
import org.broadinstitute.sting.oneoffprojects.refdata.HapmapVCFROD;
import org.broadinstitute.sting.utils.StingException;
import org.broadinstitute.sting.utils.Utils;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Class for representing arbitrary reference ordered data sets
 * <p/>
 * User: mdepristo
 * Date: Feb 27, 2009
 * Time: 10:47:14 AM
 * To change this template use File | Settings | File Templates.
 */
public class ReferenceOrderedData<ROD extends ReferenceOrderedDatum> implements Iterable<RODRecordList> { // }, RMDTrackBuilder {
    private String name;
    private File file = null;
//    private String fieldDelimiter;

    /** Header object returned from the datum */
//    private Object header = null;

    private Class<ROD> type = null; // runtime type information for object construction

    /** our log, which we want to capture anything from this class */
    private static Logger logger = Logger.getLogger(ReferenceOrderedData.class);

    /** @return a map of all available tracks we currently have access to create */
    //@Override
    public Map<String, Class> getAvailableTrackNamesAndTypes() {
        Map<String, Class> ret = new HashMap<String, Class>();
        for (RODBinding binding: Types.values())
            ret.put(binding.name, binding.type);
        return ret;
    }

    /**
     * create a RMDTrack of the specified type
     *
     * @param targetClass the target class of track
     * @param name        what to call the track
     * @param inputFile   the input file
     *
     * @return an instance of the track
     * @throws org.broadinstitute.sting.gatk.refdata.tracks.RMDTrackCreationException
     *          if we don't know of the target class or we couldn't create it
     */
    //@Override
    public RMDTrack createInstanceOfTrack(Class targetClass, String name, File inputFile) throws RMDTrackCreationException {
        return new RODRMDTrack(targetClass, name, inputFile, parse1Binding(name,targetClass.getName(),inputFile.getAbsolutePath()));
    }


    // ----------------------------------------------------------------------
    //
    // Static ROD type management
    //
    // ----------------------------------------------------------------------
    public static class RODBinding {
        public final String name;
        public final Class<? extends ReferenceOrderedDatum> type;

        public RODBinding(final String name, final Class<? extends ReferenceOrderedDatum> type) {
            this.name = name;
            this.type = type;
        }
    }

    public static HashMap<String, RODBinding> Types = new HashMap<String, RODBinding>();

    public static void addModule(final String name, final Class<? extends ReferenceOrderedDatum> rodType) {
        final String boundName = name.toLowerCase();
        if (Types.containsKey(boundName)) {
            throw new RuntimeException(String.format("GATK BUG: adding ROD module %s that is already bound", boundName));
        }
        logger.info(String.format("* Adding rod class %s", name));
        Types.put(boundName, new RODBinding(name, rodType));
    }

    static {
        // All known ROD types
        addModule("GFF", RodGenotypeChipAsGFF.class);
        addModule("dbSNP", rodDbSNP.class);
        addModule("HapMapAlleleFrequencies", HapMapAlleleFrequenciesROD.class);
        addModule("SAMPileup", rodSAMPileup.class);
        addModule("GELI", rodGELI.class);
        addModule("RefSeq", rodRefSeq.class);
        addModule("Table", TabularROD.class);
        addModule("PooledEM", PooledEMSNPROD.class);
        addModule("CleanedOutSNP", CleanedOutSNPROD.class);
        addModule("Sequenom", SequenomROD.class);
        addModule("SangerSNP", SangerSNPROD.class);
        addModule("SimpleIndel", SimpleIndelROD.class);
        addModule("PointIndel", PointIndelROD.class);
        addModule("HapMapGenotype", HapMapGenotypeROD.class);
        addModule("Intervals", IntervalRod.class);
        addModule("Variants", RodGeliText.class);
        addModule("GLF", RodGLF.class);
        addModule("VCF", RodVCF.class);
        addModule("PicardDbSNP", rodPicardDbSNP.class);
        addModule("HapmapVCF", HapmapVCFROD.class);
        addModule("Beagle", BeagleROD.class);
        addModule("Plink", PlinkRod.class);
    }


    /**
     * Parse the ROD bindings.  These are of the form of a single list of strings, each triplet of the
     * form <name>,<type>,<file>.  After this function, the List of RODs contains new RODs bound to each of
     * name, of type, ready to read from the file.  This function does check for the strings to be well formed
     * and such.
     *
     * @param bindings
     * @param rods
     */
    public static void parseBindings(ArrayList<String> bindings, List<ReferenceOrderedData<? extends ReferenceOrderedDatum>> rods) {
        // pre-process out any files that were passed in as rod binding command line options
        for (int x = 0; x < bindings.size(); x++) {
            if (new File(bindings.get(x)).exists()) {
                extractRodsFromFile(bindings, bindings.get(x));
                bindings.remove(x);
                x--;                
            }
        }
        // Loop over triplets
        for (String bindingSets : bindings) {
            String[] bindingTokens = bindingSets.split(",");
            if (bindingTokens.length % 3 != 0)
                Utils.scareUser(String.format("Invalid ROD specification: requires triplets of <name>,<type>,<file> but got %s", Utils.join(",", bindings)));

            for (int bindingSet = 0; bindingSet < bindingTokens.length; bindingSet += 3) {
                logger.info("Processing ROD bindings: " + bindings.size() + " -> " + Utils.join(" : ", bindingTokens));

                final String name = bindingTokens[bindingSet];
                final String typeName = bindingTokens[bindingSet + 1];
                final String fileName = bindingTokens[bindingSet + 2];

                ReferenceOrderedData<?> rod = parse1Binding(name, typeName, fileName);

                // check that we're not generating duplicate bindings
                for (ReferenceOrderedData rod2 : rods)
                    if (rod2.getName().equals(rod.getName()))
                        Utils.scareUser(String.format("Found duplicate rod bindings", rod.getName()));

                rods.add(rod);
            }
        }
    }

    /**
     * given an existing file, open it and append all the valid triplet lines to an existing list
     *
     * @param rodTripletList the list of existing triplets
     * @param filename       the file to attempt to extract ROD triplets from
     */
    protected static void extractRodsFromFile(List<String> rodTripletList, String filename) {
        BufferedReader str;
        try {
            str = new BufferedReader(new FileReader(new File(filename)));
        } catch (FileNotFoundException e) {
            throw new StingException("Unable to load the ROD input file " + filename,e);
        }
        String line = "NO LINES READ IN";
        try {
            while ((line = str.readLine()) != null) {
                if (line.matches(".+,.+,.+")) rodTripletList.add(line.trim());
                else logger.warn("the following file line didn't parsing into a triplet -> " + line);
            }
        } catch (IOException e) {
            throw new StingException("Failed reading the input rod file " + filename + " last line read was " + line,e);
        }
    }

    /**
     * Helpful function that parses a single triplet of <name> <type> <file> and returns the corresponding ROD with
     * <name>, of type <type> that reads its input from <file>.
     *
     * @param trackName
     * @param typeName
     * @param fileName
     * @return
     */
    private static ReferenceOrderedData<?> parse1Binding(final String trackName, final String typeName, final String fileName) {
        // Gracefully fail if we don't have the type
        if (ReferenceOrderedData.Types.get(typeName.toLowerCase()) == null)
            Utils.scareUser(String.format("Unknown ROD type: %s", typeName));

        // Lookup the type
        Class rodClass = ReferenceOrderedData.Types.get(typeName.toLowerCase()).type;

        // Create the ROD
        ReferenceOrderedData<?> rod = new ReferenceOrderedData<ReferenceOrderedDatum>(trackName.toLowerCase(), new File(fileName), rodClass );
        logger.info(String.format("Created binding from %s to %s of type %s", trackName.toLowerCase(), fileName, rodClass));
        return rod;
    }

    // ----------------------------------------------------------------------
    //
    // Constructors
    //
    // ----------------------------------------------------------------------
    public ReferenceOrderedData(final String name, File file, Class<ROD> type ) {
        this.name = name;
        this.file = file;
        this.type = type;
//        this.header = initializeROD(name, file, type);
//        this.fieldDelimiter = newROD(name, type).delimiterRegex();
    }

    public String getName() { return name; }

    public File getFile() { return file; }

    public Class<ROD> getType() { return type; }

    /**
     * Special equals override to see if this ROD is compatible with the given
     * name and type.  'Compatible' means that this ROD has the name that's passed
     * in and its data can fit into the container specified by type.
     *
     * @param name Name to check.
     * @param type Type to check.
     *
     * @return True if these parameters imply this rod.  False otherwise.
     */
    public boolean matches(String name, Class<? extends ReferenceOrderedDatum> type) {
        return this.name.equals(name) && type.isAssignableFrom(this.type);
    }

    public LocationAwareSeekableRODIterator iterator() {
        Iterator<ReferenceOrderedDatum> it;
        try {
            Method m = type.getDeclaredMethod("createIterator", String.class, java.io.File.class);
            it = (Iterator<ReferenceOrderedDatum>) m.invoke(null, name, file);
        } catch (java.lang.NoSuchMethodException e) {
            it = new RODRecordIterator(file,name,type);
        } catch (java.lang.NullPointerException e) {
            throw new RuntimeException(e);
        } catch (java.lang.SecurityException e) {
            throw new RuntimeException(e);
        } catch (java.lang.IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (java.lang.IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e);
        }
  //      return new RODIterator<ROD>(it);
        return new SeekableRODIterator(it);
    }

    // ----------------------------------------------------------------------
    //
    // Manipulations of all of the data
    //
    // ----------------------------------------------------------------------
    public ArrayList<ReferenceOrderedDatum> readAll() {
        ArrayList<ReferenceOrderedDatum> elts = new ArrayList<ReferenceOrderedDatum>();
        for ( List<ReferenceOrderedDatum> l : this ) {
            for (ReferenceOrderedDatum rec : l) {
                elts.add(rec);
            }
        }
        elts.trimToSize();
        return elts;
    }

    public static void sortRODDataInMemory(ArrayList<ReferenceOrderedDatum> data) {
        Collections.sort(data);
    }

    public static void write(ArrayList<ReferenceOrderedDatum> data, File output) throws IOException {
        final FileWriter out = new FileWriter(output);

        for (ReferenceOrderedDatum rec : data) {
            out.write(rec.repl() + "\n");
        }

        out.close();
    }

    public boolean validateFile() throws Exception {
        ReferenceOrderedDatum last = null;
        for ( List<ReferenceOrderedDatum> l : this ) {
            for (ReferenceOrderedDatum rec : l) {
                if (last != null && last.compareTo(rec) > 1) {
                    // It's out of order
                    throw new Exception("Out of order elements at \n" + last.toString() + "\n" + rec.toString());
                }
                last = rec;
            }
        }
        return true;
    }

    public void indexFile() {
        // Fixme -- get access to the linear index system from Jim
    }

    // ----------------------------------------------------------------------
    //
    // Iteration
    //
    // ----------------------------------------------------------------------
//    private class SimpleRODIterator implements Iterator<ROD> {
//        private xReadLines parser = null;
//
//        public SimpleRODIterator() {
//            try {
//                parser = new xReadLines(file);
//            } catch (FileNotFoundException e) {
//                Utils.scareUser("Couldn't open file: " + file);
//            }
//        }
//
//        public boolean hasNext() {
//            //System.out.printf("Parser has next: %b%n", parser.hasNext());
//            return parser.hasNext();
//        }
//
//        public ROD next() {
//            ROD n = null;
//            boolean success = false;
//            boolean firstFailure = true;
//
//            do {
//                final String line = parser.next();
//                //System.out.printf("Line is '%s'%n", line);
//                String parts[] = line.split(fieldDelimiter);
//
//                try {
//                    n = parseLine(parts);
//                    // Two failure conditions:
//                    // 1) parseLine throws an exception.
//                    // 2) parseLine returns null.
//                    // 3) parseLine throws a RuntimeException.
//                    // TODO: Clean this up so that all errors are handled in one spot.
//                    success = (n != null);
//                }
//                catch (MalformedGenomeLocException ex) {
//                    if (firstFailure) {
//                        Utils.warnUser("Failed to parse contig on line '" + line + "'.  The reason given was: " + ex.getMessage() + " Skipping ahead to the next recognized GenomeLoc. ");
//                        firstFailure = false;
//                   }
//                    if (!parser.hasNext())
//                        Utils.warnUser("Unable to find more valid reference-ordered data.  Giving up.");
//                }
//
//           } while (!success && parser.hasNext());
//
//            return n;
//        }
//
//        public void remove() {
//            throw new UnsupportedOperationException();
//        }
//    }

    // ----------------------------------------------------------------------
    //
    // Parsing
    //
    // ----------------------------------------------------------------------
//    private Constructor<ROD> parsing_constructor;

//    private ROD newROD(final String name, final Class<ROD> type) {
//        try {
//            return (ROD) parsing_constructor.newInstance(name);
//        } catch (java.lang.InstantiationException e) {
//            throw new RuntimeException(e);
//        } catch (java.lang.IllegalAccessException e) {
//            throw new RuntimeException(e);
//        } catch (InvocationTargetException e) {
//            throw new RuntimeException(e);
//        }
//    }

//    private Object initializeROD(final String name, final File file, final Class<ROD> type) {
//        try {
//            parsing_constructor = type.getConstructor(String.class);
//        }
//        catch (java.lang.NoSuchMethodException e) {
//            throw new RuntimeException(e);
//        }
//        ROD rod = newROD(name, type);
//        try {
//            return rod.initialize(file);
//        } catch (FileNotFoundException e) {
//            throw new RuntimeException(e);
//        }
//    }

//    private ROD parseLine(final String[] parts) {
//        //System.out.printf("Parsing GFFLine %s%n", Utils.join(" ", parts));
//        ROD obj = newROD(name, type);
//        try {
//            if (!obj.parseLine(header, parts))
//                obj = null;
//        } catch (IOException e) {
//            throw new RuntimeException("Badly formed ROD: " + e);
//        }
//        return obj;
//    }
}
