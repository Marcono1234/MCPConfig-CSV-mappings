package csv_mappings.applier;

import java.nio.file.Path;
import java.util.function.BiConsumer;

import org.slf4j.Logger;

public interface MappingLoader<T> {
    @SuppressWarnings("serial")
    class MappingLoadingException extends Exception {
        public MappingLoadingException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public MappingLoadingException(final String message) {
            super(message);
        }

        public MappingLoadingException(final Throwable cause) {
            super(cause);
        }
    }
    
    void loadMapping(Path mappingPath, ProjectType projectType, BiConsumer<String, T> mappingConsumer, Logger logger) throws MappingLoadingException;
}
