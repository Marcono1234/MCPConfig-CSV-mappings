package csv_mappings.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZipExtractor {
    private static final Logger logger = LoggerFactory.getLogger(ZipExtractor.class);
    
    /**
     * Separate wrapper class because ZipInputStream only overwrites 
     * {@link ZipInputStream#read(byte[], int, int)}, but not the other reading methods, 
     * and the documentation of {@link InflaterInputStream#read()} does not guarantee 
     * that it will call the {@code read(byte[], int, int)} method.
     */
    protected static class WrappedZipInputStream extends InputStream {
        private final ZipInputStream zipInputStream;
        private final byte[] singleByteBuffer;
        private boolean isClosed;
        
        public WrappedZipInputStream(final ZipInputStream zipInputStream) {
            this.zipInputStream = zipInputStream;
            singleByteBuffer = new byte[0];
            isClosed = false;
        }
        
        @Override
        public int read() throws IOException {
            // Guaranteed to read at least a single byte
            final int readBytes = read(singleByteBuffer);
            
            if (readBytes == -1) {
                return -1;
            }
            else {
                return Byte.toUnsignedInt(singleByteBuffer[0]);
            }
        }
        
        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return zipInputStream.read(b, off, len);
        }
        
        @Override
        public void close() throws IOException {
            if (!isClosed) {
                isClosed = true;
                zipInputStream.close();
            }
        }
    }
    
    private ZipExtractor() { }
    
    public static void extract(final ZipInputStream zipInputStream, final Path outputDirPath, final boolean replaceExistingFiles, final Consumer<Path> fileCallback) throws IOException {
        if (Files.isRegularFile(outputDirPath)) {
            throw new IllegalArgumentException("Output directory path refers to regular file");
        }
        
        final Path absoluteOutputDirPath = outputDirPath.toAbsolutePath().normalize();
        @SuppressWarnings("resource")
        final WrappedZipInputStream wrappedInputStream = new WrappedZipInputStream(zipInputStream);
        ZipEntry zipEntry;
        
        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            final String zipEntryName = zipEntry.getName();
            final Path entryOutputPath = absoluteOutputDirPath.resolve(zipEntryName).normalize();
            
            // Check with equals since empty name or '.' would result in output path
            if (!entryOutputPath.startsWith(absoluteOutputDirPath) || entryOutputPath.equals(absoluteOutputDirPath)) {
                logger.warn(String.format("Skipping entry with name '%s' which would result in an output path other than the specified one", zipEntryName));
            }
            else {
                try {
                    if (zipEntry.isDirectory()) {
                        Files.createDirectories(entryOutputPath);
                    }
                    else {
                        Files.createDirectories(entryOutputPath.getParent());
                        
                        final CopyOption[] copyOptions;
                        
                        if (replaceExistingFiles && (Files.isRegularFile(entryOutputPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entryOutputPath))) {
                            copyOptions = new CopyOption[] {StandardCopyOption.REPLACE_EXISTING};
                            logger.info(String.format("Going to replace existing file '%s'", entryOutputPath));
                        }
                        else {
                            copyOptions = new CopyOption[0];
                        }
                        
                        Files.copy(wrappedInputStream, entryOutputPath, copyOptions);
                        fileCallback.accept(entryOutputPath);
                    }
                }
                catch (final Exception exception) {
                    logger.error(String.format("Failed extracting entry with name '%s'", zipEntryName), exception);
                }
            }
        }
    }
    
    public static void extract(final File zipFilePath, final Path outputDirPath, final boolean replaceExistingFiles, final Consumer<Path> fileCallback) throws IOException {
        final ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFilePath));
        
        try {
            extract(zipInputStream, outputDirPath, replaceExistingFiles, fileCallback);
        }
        finally {
            try {
                zipInputStream.close();
            }
            catch (final IOException ioException) {
                logger.warn(String.format("Could not close zip input stream for file '%s'", zipFilePath), ioException);
            }
        }
    }
    
    // TODO: Remove
    public static void main(String[] args) throws IOException {
        final Path tempDir = Files.createTempDirectory("zip-test-temp");
        
        try {
            final File zipFile = new File(tempDir.toFile(), "test.zip");
            final ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile));
            Arrays.asList("abc", "test", "CON", "..", ".", "", "/").forEach(name -> {
                try {
                    zipOutputStream.putNextEntry(new ZipEntry(name));
                    zipOutputStream.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            zipOutputStream.close();
            
            extract(zipFile, tempDir.resolve("extracted"), false, path -> {});
        }
        finally {
            Files.walkFileTree(tempDir, new FileVisitor<Path>() {

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                    }
                    else {
                        exc.printStackTrace();
                    }
                    
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    exc.printStackTrace();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
