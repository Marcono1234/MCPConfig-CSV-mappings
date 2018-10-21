package csv_mappings.applier;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import csv_mappings.Constants;
import csv_mappings.applier.MappingLoader.MappingLoadingException;
import csv_mappings.applier.MappingProvider.MappingWithDoc;

public class CsvApplierTask extends DefaultTask {
    private ProjectType projectType;
    private Path csvDirectory;
    private Path srcDirectory;
    
    private Path srcOutDirectory;
    
    public void setProjectType(final ProjectType projectType) {
        this.projectType = projectType;
    }
    
    @Input
    public ProjectType getProjectType() {
        return projectType;
    }
    
    public void setCsvDirectory(final Path csvDirectory) {
        this.csvDirectory = csvDirectory;
    }
    
    @InputDirectory
    public Path getCsvDirectory() {
        return csvDirectory;
    }
    
    public void setSrcDirectory(final Path srcDirectory) {
        this.srcDirectory = srcDirectory;
    }
    
    @InputDirectory
    public Path getSrcDirectory() {
        return srcDirectory;
    }
    
    public void setSrcOutDirectory(final Path srcOutDirectory) {
        this.srcOutDirectory = srcOutDirectory;
    }
    
    @OutputDirectory
    public Path getSrcOutDirectory() {
        return srcOutDirectory;
    }
    
    private MappingProvider mappingProvider;
    
    public CsvApplierTask() {
        setGroup(Constants.TASK_GROUP_NAME);
        setDescription("Applies CSV mappings");
    }
    
    @TaskAction
    public void applyCsvs() throws IOException {
        checkDirectory(csvDirectory, "CSV");
        checkDirectory(srcDirectory, "Source code");
        
        checkNoDescendants(srcDirectory, srcOutDirectory);
        checkNoDescendants(csvDirectory, srcOutDirectory);
        
        if (Files.exists(srcOutDirectory)) {
            if (Files.isDirectory(srcOutDirectory)) {
                getLogger().info(String.format("Source output directory '%s' already exists; clearing it", srcOutDirectory.toAbsolutePath()));
                // Based on code from https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileVisitor.html
                Files.walkFileTree(srcOutDirectory, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    
                    @Override
                    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                        if (exc == null) {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                        else {
                            throw exc;
                        }
                    }
                });
                
                // Have to recreate directory again because it was deleted
                Files.createDirectories(srcOutDirectory);
            }
            else {
                throw new IllegalArgumentException(String.format("Source output path '%s' is not a directory", srcOutDirectory.toAbsolutePath()));
            }
        }
        else {
            getLogger().info(String.format("Source output directory '%s' does not exist; creating it", srcOutDirectory));
            Files.createDirectories(srcOutDirectory);
        }
        
        mappingProvider = createMappingProvider();
        final List<CompletableFuture<?>> remappingFutures = new ArrayList<>();
        
        Files.walkFileTree(srcDirectory, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                // Ignore start directory
                if (!Files.isSameFile(dir, srcDirectory)) {
                    final Path targetDir = resolveRelativize(dir, srcDirectory, srcOutDirectory);
                    Files.copy(dir, targetDir);
                }
                
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                if (exc != null) {
                    getLogger().error(String.format("Could not completely process directory '%s'", dir), exc);
                }
                
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                remappingFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        processFile(file);
                    }
                    catch (final IOException ioException) {
                        throw new RuntimeException(ioException);
                    }
                }).whenComplete((result, exception) -> {
                    if (exception != null) {
                        getLogger().error(String.format("Failed processing file '%s'", file), exception);
                    }
                }));
                
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) throws IOException {
                getLogger().error(String.format("Could not process file '%s'", file), exc);
                return FileVisitResult.CONTINUE;
            }
        });
        
        // Wait for all remapping tasks to be completed
        remappingFutures.forEach(future -> {
            try {
                future.join();
            }
            catch (final Exception exception) {
               // Don't do anything, exception was already handled by whenComplete()
            }
        });
    }
    
    private void checkDirectory(final Path directory, final String directoryDescription) throws IllegalArgumentException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException(
                String.format(
                    "%s directory '%s' does not exist",
                    directoryDescription,
                    directory.toAbsolutePath()
                )
            );
        }
    }
    
    private void checkNoDescendants(final Path a, final Path b) throws IllegalArgumentException, IOException {
        if (areDescendants(a, b)) {
            throw new IllegalArgumentException(
                String.format(
                    "Paths '%s' and '%s' are descendants",
                    a.toAbsolutePath().toString(),
                    b.toAbsolutePath().toString()
                )
            );
        }
    }
    
    /**
     * @param a
     *      The first path
     * @param b
     *      The second path
     * @return Whether: The real versions of a and b are either the same or 
     *      one is the descendant of the other
     * @throws IOException
     *      When the real version of the paths could not be determined, see 
     *      {@link Path#toRealPath(java.nio.file.LinkOption...)}
     */
    private static boolean areDescendants(final Path a, final Path b) throws IOException {
        final Path realA = a.toRealPath();
        final Path realB = b.toRealPath();
        
        return realA.startsWith(realB) || realB.startsWith(realA);
    }
    
    protected MappingProvider createMappingProvider() {
        class MappingLoaderRunnable<T> implements Runnable {
            /**
             * Path relative to {@link CsvApplierTask#csvDirectory}
             */
            private final Path relativePath;
            private final Path resolvedPath;
            private final MappingLoader<T> mappingLoader;
            private final Map<String, T> map;
            
            public MappingLoaderRunnable(final Path relativePath, final MappingLoader<T> mappingLoader, final Map<String, T> map) {
                this.relativePath = relativePath;
                this.resolvedPath = csvDirectory.resolve(this.relativePath);
                this.mappingLoader = mappingLoader;
                this.map = map;
            }
            
            public boolean doesFileExist() {
                return Files.isRegularFile(resolvedPath);
            }
            
            public Path getRelativePath() {
                return relativePath;
            }

            @Override
            public void run() {
                try {
                    // TODO: Can logger be used by other threads?
                    mappingLoader.loadMapping(resolvedPath, projectType, map::put, getLogger());
                }
                catch (final MappingLoadingException mappingLoadingException) {
                    throw new RuntimeException(mappingLoadingException);
                }
            }
        }
        
        final Map<String, MappingWithDoc> fieldMappings = new HashMap<>();
        final Map<String, MappingWithDoc> methodMappings = new HashMap<>();
        final Map<String, String> paramMappings = new HashMap<>();
        
        final List<MappingLoaderRunnable<?>> mappingLoaders = Arrays.asList(
            new MappingLoaderRunnable<>(Paths.get("fields.csv"), CsvMappingLoader.FIELD, fieldMappings),
            new MappingLoaderRunnable<>(Paths.get("methods.csv"), CsvMappingLoader.METHOD, methodMappings),
            new MappingLoaderRunnable<>(Paths.get("params.csv"), CsvMappingLoader.PARAM, paramMappings)
        );
        
        final List<MappingLoaderRunnable<?>> applicableLoaders = mappingLoaders.stream()
            .filter(MappingLoaderRunnable::doesFileExist)
            .collect(Collectors.toList());
        
        if (applicableLoaders.isEmpty()) {
            throw new RuntimeException(String.format(
                "Did not find any of the mapping files: %s",
                mappingLoaders.stream()
                    .map(MappingLoaderRunnable::getRelativePath)
                    .map(Path::toString)
                    .collect(Collectors.joining(", "))
            ));
        }
        
        class SubmittedLoader {
            private final MappingLoaderRunnable<?> loader;
            private final CompletableFuture<?> future;
            
            public SubmittedLoader(final MappingLoaderRunnable<?> loader, final CompletableFuture<?> future) {
                this.loader = loader;
                this.future = future;
            }
            
            public MappingLoaderRunnable<?> getLoader() {
                return loader;
            }
            
            public CompletableFuture<?> getFuture() {
                return future;
            }
        }
        
        final List<SubmittedLoader> submittedLoaders = new ArrayList<>();
        applicableLoaders.forEach(loader -> {
            final CompletableFuture<?> future = CompletableFuture.runAsync(loader);
            submittedLoaders.add(new SubmittedLoader(loader, future));
        });
        
        submittedLoaders.forEach(submittedLoader -> {
            try {
                submittedLoader.getFuture().join();
            }
            catch (final CompletionException completionException) {
                getLogger().error(String.format("Mapping loader for '%s' failed", submittedLoader.getLoader().getRelativePath()), completionException);
            }
        });
        
        return new MappingProvider(fieldMappings, methodMappings, paramMappings);
    }
    
    protected void processFile(final Path file) throws IOException {
        final int bufferSize = 2048;
        final char[] buffer = new char[bufferSize];
        int readChars;
        
        try (Writer writer = Files.newBufferedWriter(resolveRelativize(file, srcDirectory, srcOutDirectory))) {
            final Reader reader = Files.newBufferedReader(file);
            
            try {
                final Mapper<IOException> remapper = new Mapper<>(writer::append, mappingProvider);
                
                while ((readChars = reader.read(buffer)) != -1) {
                    remapper.append(buffer, 0, readChars);
                }
                
                remapper.finish();
            }
            finally {
                try {
                    reader.close();
                }
                catch (final IOException ioException) {
                    getLogger().warn(String.format("Could not close file '%s'", file.toAbsolutePath()));
                }
            }
        }
    }
    
    private static Path resolveRelativize(final Path path, final Path currentParent, final Path newParent) throws IOException {
        final Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        final Path realCurrentParent = currentParent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        
        return newParent.resolve(realCurrentParent.relativize(realPath));
    }
}