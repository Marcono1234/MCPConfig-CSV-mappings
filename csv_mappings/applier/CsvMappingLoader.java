package csv_mappings.applier;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import csv_mappings.applier.CsvReader.CharSupplierException;
import csv_mappings.applier.CsvReader.DataType;
import csv_mappings.applier.CsvReader.ParseException;
import csv_mappings.applier.MappingProvider.MappingWithDoc;

public abstract class CsvMappingLoader<T> implements MappingLoader<T> {
    public static final MappingLoader<MappingWithDoc> FIELD = new CsvMappingLoader<MappingWithDoc>(
        Arrays.asList("searge", "name", "side", "desc")
    ) {
        @Override
        protected void transformRow(final List<String> values, final BiConsumer<String, MappingWithDoc> mappingConsumer, final ProjectType projectType) {
            final int side = Integer.parseInt(values.get(2));
            
            if (projectType.applies(side)) {
                mappingConsumer.accept(values.get(0), createMappingWithDoc(values.get(1), values.get(3)));
            }
        }
    };
    public static final MappingLoader<MappingWithDoc> METHOD = new CsvMappingLoader<MappingWithDoc>(
        Arrays.asList("searge", "name", "side", "desc")
    ) {
        @Override
        protected void transformRow(final List<String> values, final BiConsumer<String, MappingWithDoc> mappingConsumer, final ProjectType projectType) {
            final int side = Integer.parseInt(values.get(2));
            
            if (projectType.applies(side)) {
                mappingConsumer.accept(values.get(0), createMappingWithDoc(values.get(1), values.get(3)));
            }
        }
    };
    public static final MappingLoader<String> PARAM = new CsvMappingLoader<String>(
        Arrays.asList("param", "name", "side")
    ) {
        @Override
        protected void transformRow(final List<String> values, final BiConsumer<String, String> mappingConsumer, final ProjectType projectType) {
            final int side = Integer.parseInt(values.get(2));
            
            if (projectType.applies(side)) {
                mappingConsumer.accept(values.get(0), verifySaneMapping(values.get(1)));
            }
        }
    };
    
    private final List<String> expectedHeaders;
    
    public CsvMappingLoader(final List<String> expectedHeaders) {
        this.expectedHeaders = expectedHeaders;
    }
    
    @Override
    public void loadMapping(final Path mappingPath, final ProjectType projectType, final BiConsumer<String, T> mappingConsumer, final Logger logger) throws MappingLoadingException {
        final Reader reader;
        
        try {
            reader = Files.newBufferedReader(mappingPath);
        }
        catch (final IOException exception) {
            throw new MappingLoadingException(exception);
        }
        
        try {
            final CsvReader csvReader = CsvReader.createCsvReader(reader, 2048);
            verifyHeaders(csvReader);
            
            try {
                if (csvReader.hasMore()) {
                    /*
                     * Have to consume line break since readRows expects to start 
                     * at the beginning of a row
                     */
                    csvReader.nextRow();
                    readRows(csvReader, mappingConsumer, projectType, logger);
                }
            }
            catch (final CharSupplierException | ParseException exception) {
                throw new MappingLoadingException(exception);
            }
        }
        finally {
            try {
                reader.close();
            }
            catch (final IOException ioException) {
                logger.warn(
                    String.format("Could not close mapping file '%s'", mappingPath.toAbsolutePath().toString()),
                    ioException
                );
            }
        }
    }
    
    /**
     * <p>Reads mapping rows using the CSV reader and, if the mappings apply to the given 
     * project type, passes them to the given mapping consumer.</p>
     * 
     * <p>The CSV reader has to be at the beginning of a row (column index 0).</p>
     * 
     * @param csvReader
     *      The reader to use for reading
     * @param mappingConsumer
     *      Consumer which consumes the read mapping
     * @param projectType
     *      The project type for which mappings are relevant
     * @param logger
     *      Logger to use for logging
     * @throws CharSupplierException
     *      When the char supplier throws an exception
     * @throws ParseException
     *      When the CSV data is malformed
     */
    protected void readRows(final CsvReader csvReader, final BiConsumer<String, T> mappingConsumer, final ProjectType projectType, final Logger logger) throws CharSupplierException, ParseException {
        final List<String> rowValues = new ArrayList<>();
        
        // Empty file
        if (csvReader.isTrailingEmptyRow()) {
            return;
        }
        
        while (true) {
            final DataType dataType = csvReader.peekOrConsumeNext(true);
            
            if (dataType.isRowFinished()) {
                try {
                    transformRow(rowValues, mappingConsumer, projectType);
                }
                catch (final RuntimeException exception) {
                    logger.warn(String.format("Failed transforming row with values '%s'", rowValues), exception);
                }
                
                rowValues.clear();
            }
            
            if (dataType == DataType.VALUE) {
                final StringBuilder valueStringBuilder = new StringBuilder();
                csvReader.readValue(valueStringBuilder::append);
                
                rowValues.add(valueStringBuilder.toString());
            }
            else if (dataType == DataType.END) {
                break;
            }
        }
    }
    
    protected abstract void transformRow(List<String> values, BiConsumer<String, T> mappingConsumer, ProjectType projectType);
    
    private void verifyHeaders(final CsvReader reader) throws IllegalArgumentException {
        for (final String expectedHeader : expectedHeaders) {
            final StringBuilder stringBuilder = new StringBuilder(expectedHeader.length());
            
            try {
                reader.readValue(stringBuilder::append);
            }
            catch (final ParseException | CharSupplierException exception) {
                throw new IllegalArgumentException(
                    String.format("Could not read value for expected '%s'", expectedHeader),
                    exception
                );
            }
            
            final String actualHeader = stringBuilder.toString();
            
            if (!expectedHeader.equals(actualHeader)) {
                throw new IllegalArgumentException(String.format("Expected '%s', found '%s'", expectedHeader, actualHeader));
            }
        }
    }
    
    protected static String verifySaneMapping(final String mapping) throws IllegalArgumentException {
        if (MappingSanitizer.isValidIdentifier(mapping, true)) {
            return mapping;
        }
        else {
            throw new IllegalArgumentException(String.format("Mapping '%s' is not sane", mapping));
        }
    }
    
    protected static MappingWithDoc createMappingWithDoc(final String mapping, final String documentation) throws IllegalArgumentException {
        return new MappingWithDoc(
            verifySaneMapping(mapping),
            documentation.isEmpty()
                ? null
                : MappingSanitizer.escapeCommentContent(documentation.replace("\\n", "\n"))
        );
    }
    
    // TODO Remove
    public static void main(String[] args) throws IOException, MappingLoadingException {
        final Path fieldMappingsCsv = Files.createTempFile("field-mappings-test", ".csv");
        fieldMappingsCsv.toFile().deleteOnExit();
        
        Files.write(fieldMappingsCsv, Collections.singleton(String.join("\r\n",
            Arrays.asList(
                "searge,name,side,desc",
                "a,mapped,0,test",
                "b,mapped2,0,test",
                "c,quoted,0,\"quoted and \"\" quote\"",
                "d,invalid identifier,0,doc",
                "nodoc,ident,0,",
                // Use unicode escape \u002f for '/' due to Eclipse bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=125741
                "test,transformed_doc,0,text*\u002f"
            )
        )));
        
        final Map<String, MappingWithDoc> fieldMappings = new HashMap<>();
        FIELD.loadMapping(fieldMappingsCsv, ProjectType.CLIENT, fieldMappings::put, LoggerFactory.getLogger("mapping-test-logger"));
        
        fieldMappings.forEach((key, value) -> {
            final String docString = value.getDocumentation().orElse(null);
            
            System.out.println(String.format(
                "%s\n  Map: %s%s",
                key,
                value.getMapping(),
                docString == null ? "" : "\n  Doc: " + docString
            ));
        });
        System.out.println(fieldMappings.size());
    }
}