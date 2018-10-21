package csv_mappings.downloader;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.google.gson.stream.JsonReader;

public enum MappingType {
    SNAPSHOT("snapshot") {
        @Override
        public Supplier<URL> createUrlSupplier(final MinecraftVersion version, final JsonReader jsonReader, final boolean includeDoc) throws IOException {
            return readFirstMappingVersion(version, jsonReader, includeDoc);
        }
    },
    STABLE("stable") {
        @Override
        public Supplier<URL> createUrlSupplier(final MinecraftVersion version, final JsonReader jsonReader, final boolean includeDoc) throws IOException {
            return readFirstMappingVersion(version, jsonReader, includeDoc);
        }
    }
    ;
    
    public static final List<MappingType> VALUES_LIST = Collections.unmodifiableList(Arrays.asList(values()));
    
    private final String key;
    
    private MappingType(final String key) {
        this.key = key;
    }
    
    public abstract Supplier<URL> createUrlSupplier(MinecraftVersion version, JsonReader jsonReader, boolean includeDoc) throws IOException;
    
    /**
     * <p>Reads the first mapping version from the JSON reader, which should be in front 
     * the beginning of an array containing either ints or strings. When the array is 
     * empty, {@code null} is returned; otherwise a supplier which creates the URL.</p>
     * 
     * <p>This method exists for convenience because both stable and snapshot mapping 
     * versions are represented by an array containing ints, with the latest mapping 
     * version as first element. However each {@code MappingType} still has to implement 
     * the method {@link #createUrlSupplier(MinecraftVersion, JsonReader, boolean)} 
     * because the mapping versions have different meanings. Stable versions are just an 
     * incrementing int, while snapshot versions are based on the date they were created. 
     * Therefore if, at some point, the version order changes, the mapping types have 
     * to parse and order the versions themselves.</p>
     * 
     * @param version
     *      The Minecraft version to create the URL for
     * @param jsonReader
     *      The JSON reader which is in front of an array containing the versions
     * @param includeDoc
     *      Whether the mappings should include the documentation
     * @return
     *      The created supplier or {@code null} when no mapping versions exist
     * @throws IOException
     *      When reading from the JSON reader failed
     */
    protected Supplier<URL> readFirstMappingVersion(final MinecraftVersion version, final JsonReader jsonReader, final boolean includeDoc) throws IOException {
        jsonReader.beginArray();
        Supplier<URL> urlSupplier = null;
        
        try {
            if (jsonReader.hasNext()) {
                // Can read int using nextString()
                urlSupplier = createUrlSupplier(version, jsonReader.nextString(), includeDoc);
            }
        }
        finally {
            while (jsonReader.hasNext()) {
                jsonReader.skipValue();
            }
            
            jsonReader.endArray();
        }
        
        return urlSupplier;
    }
    
    protected Supplier<URL> createUrlSupplier(final MinecraftVersion version, final String mappingVersion, final boolean includeDoc) {
        /*
         * Examples:
         * /mcp_stable/43-1.13/mcp_stable-43-1.13.zip
         * /mcp_snapshot/20181016-1.13.1/mcp_snapshot-20181016-1.13.1.zip
         * /mcp_snapshot_nodoc/20181016-1.13.1/mcp_snapshot_nodoc-20181016-1.13.1.zip
         */
        return () -> {
            String mappingTypeString = "mcp_" + key;
            final String versionString = mappingVersion + "-" + version.getStringRepresentation();
            
            if (!includeDoc) {
                mappingTypeString += "_nodoc";
            }
            
            return UrlHelper.createValidUrl(String.format(
                DownloadConstants.CSV_ROOT_URL + "/%1$s/%2$s/%1$s-%2$s.zip",
                mappingTypeString,
                versionString
            ));
        };
    }
    
    public static MappingType getForKey(final String key) {
        for (final MappingType mappingType : VALUES_LIST) {
            if (mappingType.key.equals(key)) {
                return mappingType;
            }
        }
        
        return null;
    }
    
    // TODO: Remove
    public static void main(String[] args) throws IOException {
        final Supplier<JsonReader> testJsonReaderFactory = () -> new JsonReader(new StringReader("[25, 300],\"some more data\""));
        final MinecraftVersion minecraftVersion = MinecraftVersion.fromString("1.13.1");
        
        System.out.println(STABLE.createUrlSupplier(minecraftVersion, testJsonReaderFactory.get(), true).get());
        System.out.println(SNAPSHOT.createUrlSupplier(minecraftVersion, testJsonReaderFactory.get(), false).get());
    }
}