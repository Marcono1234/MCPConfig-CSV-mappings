package csv_mappings.applier;

import java.util.Map;
import java.util.Optional;

/**
 * <p>Class providing mappings for fields, methods and parameters and 
 * documentation for fields and methods.</p>
 * 
 * <p>The format of the names is up to the user. They can for example 
 * be fully qualified, or just the names if they are unique across all 
 * classes.</p>
 */
public class MappingProvider {
    public static class MappingWithDoc {
        private final String mapping;
        private final Optional<String> documentation;
        
        public MappingWithDoc(final String mapping, final String documentation) {
            this.mapping = mapping;
            this.documentation = Optional.ofNullable(documentation);
        }
        
        public String getMapping() {
            return mapping;
        }
        
        public Optional<String> getDocumentation() {
            return documentation;
        }
    }
    
    private final Map<String, MappingProvider.MappingWithDoc> fieldMappings;
    private final Map<String, MappingProvider.MappingWithDoc> methodMappings;
    private final Map<String, String> paramMappings;
    
    public MappingProvider(final Map<String, MappingProvider.MappingWithDoc> fieldMappings, final Map<String, MappingProvider.MappingWithDoc> methodMappings, final Map<String, String> paramMappings) {
        this.fieldMappings = fieldMappings;
        this.methodMappings = methodMappings;
        this.paramMappings = paramMappings;
    }
    
    private static Optional<String> getMapping(final Map<String, MappingProvider.MappingWithDoc> mappings, final String name) {
        final MappingProvider.MappingWithDoc mappingData = mappings.get(name);
        
        if (mappingData == null) {
            return Optional.empty();
        }
        else {
            return Optional.of(mappingData.getMapping());
        }
    }
    
    private static Optional<String> getDoc(final Map<String, MappingProvider.MappingWithDoc> mappings, final String name) {
        final MappingProvider.MappingWithDoc mappingData = mappings.get(name);
        
        if (mappingData == null) {
            return Optional.empty();
        }
        else {
            return mappingData.getDocumentation();
        }
    }
    
    public Optional<String> getFieldMapping(final String name) {
        return getMapping(fieldMappings, name);
    }
    
    public Optional<String> getFieldDoc(final String name) {
        return getDoc(fieldMappings, name);
    }
    
    public Optional<String> getMethodMapping(final String name) {
        return getMapping(methodMappings, name);
    }
    
    public Optional<String> getMethodDoc(final String name) {
        return getDoc(methodMappings, name);
    }
    
    public Optional<String> getParamMapping(final String name) {
        return Optional.ofNullable(paramMappings.get(name));
    }
}