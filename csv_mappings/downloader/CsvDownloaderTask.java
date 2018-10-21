package csv_mappings.downloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import com.google.gson.stream.JsonReader;

import csv_mappings.Constants;

public class CsvDownloaderTask extends DefaultTask {
    private static enum AcceptType {
        ACCEPT,
        DENY,
        TENTATIVE
    }
    
    public static enum SelectionType {
        STABLE_ONLY() {
            @Override
            protected AcceptType accepts(final MappingType mappingType) {
                return mappingType == MappingType.STABLE
                    ? AcceptType.ACCEPT
                    : AcceptType.DENY;
            }
        },
        STABLE_ELSE_SNAPSHOT() {
            @Override
            protected AcceptType accepts(final MappingType mappingType) {
                switch (mappingType) {
                    case STABLE:
                        return AcceptType.ACCEPT;
                    case SNAPSHOT:
                        return AcceptType.TENTATIVE;
                    default:
                        return AcceptType.DENY;
                }
            }
        },
        SNAPSHOT_ELSE_STABLE() {
            @Override
            protected AcceptType accepts(final MappingType mappingType) {
                switch (mappingType) {
                    case SNAPSHOT:
                        return AcceptType.ACCEPT;
                    case STABLE:
                        return AcceptType.TENTATIVE;
                    default:
                        return AcceptType.DENY;
                }
            }
        }
        ;
        
        protected abstract AcceptType accepts(MappingType mappingType);
    }
    
    private Path outDirectory;
    private MinecraftVersion version;
    private SelectionType mappingSelectionType;
    private boolean shouldIncludeDoc;
    private boolean shouldAllowOlderMappings;
    
    public CsvDownloaderTask() {
        mappingSelectionType = SelectionType.STABLE_ELSE_SNAPSHOT;
        shouldIncludeDoc = true;
        shouldAllowOlderMappings = true;
        setGroup(Constants.TASK_GROUP_NAME);
        setDescription("Downloads CSV mappings.");
    }
    
    @OutputDirectory
    public Path getOutDirectory() {
        return outDirectory;
    }
    
    public void setOutDirectory(final Path outDirectory) {
        this.outDirectory = outDirectory;
    }
    
    @Input
    public MinecraftVersion getVersion() {
        return version;
    }
    
    public void setVersion(final MinecraftVersion version) {
        this.version = version;
    }
    
    @Input
    public SelectionType getMappingSelectionType() {
        return mappingSelectionType;
    }
    
    public void setMappingSelectionType(final SelectionType mappingSelectionType) {
        this.mappingSelectionType = mappingSelectionType;
    }
    
    @Input
    public boolean shouldIncludeDoc() {
        return shouldIncludeDoc;
    }
    
    public void setShouldIncludeDoc(final boolean shouldIncludeDoc) {
        this.shouldIncludeDoc = shouldIncludeDoc;
    }
    
    @Input
    public boolean shouldAllowOlderMappings() {
        return shouldAllowOlderMappings;
    }
    
    public void setShouldAllowOlderMappings(final boolean shouldAllowOlderMappings) {
        this.shouldAllowOlderMappings = shouldAllowOlderMappings;
    }
    
    @TaskAction
    public void downloadCsvs() throws IOException {
        final URL mappingZipUrl = getLatestMappingVersion();
        getLogger().info(String.format("Downloading zipped CSVs from '%s'", mappingZipUrl));
        final ZipInputStream mappingZipStream = new ZipInputStream(mappingZipUrl.openStream());
        
        try {
            getLogger().info(String.format("Extracting to '%s'", outDirectory));
            ZipExtractor.extract(mappingZipStream, outDirectory, true, entryPath -> {});
        }
        finally {
            try {
                mappingZipStream.close();
            }
            catch (final IOException ioException) {
                getLogger().warn(String.format("Could not close input stream of downloaded mapping zip file", ioException));
            }
        }
    }
    
    private URL getLatestMappingVersion() throws IOException {
        final URL url = UrlHelper.createValidUrl(DownloadConstants.CSV_ROOT_URL + "/versions.json");
        final JsonReader versionDataReader = new JsonReader(new BufferedReader(new InputStreamReader(url.openStream())));
        
        try {
            Map.Entry<MinecraftVersion, URL> olderVersionData = null;
            versionDataReader.beginObject();
            
            while (versionDataReader.hasNext()) {
                final MinecraftVersion readVersion = MinecraftVersion.fromString(versionDataReader.nextName());
                final int comparisonResult = readVersion.compareTo(version);
                
                if (comparisonResult == 0) {
                    versionDataReader.beginObject();
                    return getLatestMappingVersionUrl(versionDataReader);
                }
                else if (shouldAllowOlderMappings && comparisonResult < 0 && (olderVersionData == null || olderVersionData.getKey().compareTo(readVersion) < 0)) {
                    final URL mappingUrl;
                    
                    try {
                        versionDataReader.beginObject();
                        mappingUrl = getLatestMappingVersionUrl(versionDataReader);
                        // Have to end object because more version entries might be parsed afterwards
                        versionDataReader.endObject();
                    }
                    catch (final IOException ioException) {
                        throw new UncheckedIOException(ioException);
                    }
                    
                    olderVersionData = new AbstractMap.SimpleEntry<>(readVersion, mappingUrl);
                }
                else {
                    versionDataReader.skipValue();
                }
            }
            
            if (olderVersionData != null) {
                getLogger().info(String.format("Using older mappings for version '%s'", olderVersionData.getKey()));
                return olderVersionData.getValue();
            }
            
            // End object to detect cut off / malformed JSON data
            versionDataReader.endObject();
            
            throw new IllegalArgumentException(String.format("Version '%s' has no mappings", version));
        }
        finally {
            try {
                versionDataReader.close();
            }
            catch (final IOException ioException) {
                getLogger().warn("Could not close verion data reader", ioException);
            }
        }
    }
    
    private URL getLatestMappingVersionUrl(final JsonReader jsonReader) throws IOException, IllegalArgumentException {
        Supplier<URL> mappingUrlSupplier = null;
        
        while (jsonReader.hasNext()) {
            final String mappingTypeKey = jsonReader.nextName();
            final MappingType mappingType = MappingType.getForKey(mappingTypeKey);
            
            if (mappingType == null) {
                getLogger().warn(String.format("Ignoring unknown mapping type key '%s'", mappingType));
                jsonReader.skipValue();
            }
            else {
                final AcceptType acceptType = mappingSelectionType.accepts(mappingType);
                
                if (acceptType != AcceptType.DENY) {
                    final Supplier<URL> urlSupplier = mappingType.createUrlSupplier(version, jsonReader, shouldIncludeDoc);
                    
                    if (urlSupplier != null) {
                        mappingUrlSupplier = urlSupplier;
                        
                        if (acceptType == AcceptType.ACCEPT) {
                            break;
                        }
                    }
                }
            }
        }
        
        if (mappingUrlSupplier == null) {
            throw new IllegalArgumentException(String.format("No selectable mappings for version '%s' exist", version));
        }
        else {
            return mappingUrlSupplier.get();
        }
    }
}
