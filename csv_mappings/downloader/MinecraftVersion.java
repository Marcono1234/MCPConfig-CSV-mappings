package csv_mappings.downloader;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Implements Serializable for Gradle since version is used as task input
public class MinecraftVersion implements Comparable<MinecraftVersion>, Serializable {
    private static final long serialVersionUID = 1L;
    
    private final List<Integer> versionData;
    
    protected MinecraftVersion(final List<Integer> versionData) {
        this.versionData = versionData;
    }
    
    @Override
    public int compareTo(final MinecraftVersion other) {
        final int minLength = Math.min(versionData.size(), other.versionData.size());
        
        for (int index = 0; index < minLength; index++) {
            final int versionPieceResult = versionData.get(index) - other.versionData.get(index);
            
            if (versionPieceResult != 0) {
                return versionPieceResult;
            }
        }
        
        // So far versions are equal, but maybe one is a sub-version
        return versionData.size() - other.versionData.size();
    }
    
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof MinecraftVersion
            ? compareTo((MinecraftVersion) obj) == 0
            : false;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(versionData.toArray());
    }
    
    public String getStringRepresentation() {
        return versionData.stream()
            .map(integer -> Integer.toString(integer))
            .collect(Collectors.joining("."));
    }
    
    @Override
    public String toString() {
        return getStringRepresentation();
    }
    
    public static MinecraftVersion fromString(final String stringRepresentation) throws IllegalArgumentException {
        return new MinecraftVersion(
            Arrays.stream(stringRepresentation.split(Pattern.quote(".")))
                .map(Integer::parseInt)
                .collect(Collectors.toList())
        );
    }
}
