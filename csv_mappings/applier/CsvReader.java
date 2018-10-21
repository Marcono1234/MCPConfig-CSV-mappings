package csv_mappings.applier;

import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * <p>Reads characters in CSV format as described in 
 * <a href="https://tools.ietf.org/html/rfc4180#section-2">RFC 4180</a> (but not 
 * the ABNF grammar). 
 * Additionally it 
 * <ul>
 *  <li>considers empty lines as containing the empty string ({@code ""})</li>
 *  <li>requires that the closing quote of a quoted value is followed by either a 
 *      colon, a line break or the end of the characters</li>
 * </ul>
 * But it does not enforce that all rows have the same number of columns. The 
 * method {@link #isTrailingEmptyRow()} can be used to detect an empty row at the 
 * end of the characters. This might be useful when this empty row is not intended 
 * as empty value and should be ignored.</p>
 * 
 * <p>There are methods for peeking at the following content:
 * <ul>
 *  <li>{@link #hasMore()}</li>
 *  <li>{@link #isNextNewRow()}</li>
 *  <li>{@link #isNextValue()}</li>
 *  <li>{@link #isTrailingEmptyRow()}</li>
 * </ul>
 * And methods for consuming the content:
 * <ul>
 *  <li>{@link #nextRow()}</li>
 *  <li>{@link #readValue(Consumer)}</li>
 *  <li>{@link #skipValue()}</li>
 * </ul>
 * Trying to use the consuming methods when they are not applicable, for example 
 * trying to read a value when the end of the row is reached, throws 
 * {@link ParseException}s.</p>
 * 
 * <p>Usually the method {@link #peekOrConsumeNext(boolean)} should be used 
 * when working with the reader since it performs the required peeking and 
 * consuming.</p>
 */
public class CsvReader {
    private static final Consumer<Object> NO_OP_CONSUMER = value -> {};
    
    public static interface CharSupplier {
        boolean hasNext() throws CharSupplierException;
        CharSequence next() throws CharSupplierException, IllegalStateException;
    }
    
    @SuppressWarnings("serial")
    public static class CharSupplierException extends Exception {
        public CharSupplierException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public CharSupplierException(final String message) {
            super(message);
        }

        public CharSupplierException(final Throwable cause) {
            super(cause);
        }
    }
    
    @SuppressWarnings("serial")
    public static class ParseException extends Exception {
        private int charIndex;
        private int rowIndex;
        private int columnIndex;
        
        public ParseException(final CsvReader reader, final String message, final Throwable cause) {
            super(appendPosition(reader, message), cause);
            setPosition(reader);
        }

        public ParseException(final CsvReader reader, final String message) {
            super(appendPosition(reader, message));
            setPosition(reader);
        }

        public ParseException(final CsvReader reader, final Throwable cause) {
            super(appendPosition(reader, null), cause);
            setPosition(reader);
        }
        
        private void setPosition(final CsvReader reader) {
            charIndex = reader.charIndex;
            rowIndex = reader.rowIndex;
            columnIndex = reader.columnIndex;
        }
        
        private static String appendPosition(final CsvReader reader, final String message) {
            final String positionInformation = String.format(
                "char index %d, row %d, column %d",
                reader.charIndex,
                reader.rowIndex,
                reader.columnIndex
            );
            
            if (message == null) {
                return "At " + positionInformation;
            }
            else {
                return message + "; at " + positionInformation;
            }
        }
        
        public int getCharIndex() {
            return charIndex;
        }
        
        public int getRowIndex() {
            return rowIndex;
        }
        
        public int getColumnIndex() {
            return columnIndex;
        }
    }
    
    public static enum DataType {
        VALUE(CsvReader::isNextValue, false),
        ROW(CsvReader::isNextNewRow, true),
        END(reader -> !reader.hasMore(), true)
        ;
        
        public static final List<DataType> VALUES_LIST = Collections.unmodifiableList(Arrays.asList(values()));
        
        protected static interface ReaderCheckMethod {
            boolean check(CsvReader reader) throws CharSupplierException;
        }
        
        private final ReaderCheckMethod checkMethod;
        private final boolean isRowFinished;
        
        private DataType(final ReaderCheckMethod checkMethod, final boolean isRowFinished) {
            this.checkMethod = checkMethod;
            this.isRowFinished = isRowFinished;
        }
        
        protected boolean check(final CsvReader reader) throws CharSupplierException {
            return checkMethod.check(reader);
        }
        
        /**
         * @return Whether this data type means that the current row is finished
         */
        public boolean isRowFinished() {
            return isRowFinished;
        }
    }
    
    private final String separatorString;
    private final String quotationString;
    private final String lineBreakString;
    
    private final List<String> unquotedValueEnds;
    
    private final CharSupplier charSupplier;
    // TODO: Is StringBuilder too inefficient when deleting char-wise?
    private final StringBuilder valueBuffer;
    private boolean reachedEnd;
    
    private int charIndex;
    private int rowIndex;
    private int columnIndex;
    
    /**
     * The char supplier provides the characters which are parsed by this reader. 
     * The reader does not keep a reference to the supplied char sequence, which allows 
     * reusing it after it has been supplied. Once there are no more chars the method 
     * {@link CharSupplier#hasNext()} has to return {@code false}. Afterwards the 
     * supplier is not used anymore, even if it returns {@code true} for {@code hasNext} 
     * again.
     * 
     * @param charSupplier
     *     Supplier for the characters which should be read 
     */
    public CsvReader(final CharSupplier charSupplier) {
        this.charSupplier = charSupplier;
        
        separatorString = ",";
        quotationString = "\"";
        lineBreakString = "\r\n";
        unquotedValueEnds = Arrays.asList(separatorString, lineBreakString);
        
        valueBuffer = new StringBuilder();
        reachedEnd = false;
        
        charIndex = 0;
        rowIndex = 0;
        columnIndex = 0;
    }
    
    private void ensureBufferSize(final int bufferSize) throws CharSupplierException {
        while (!reachedEnd && valueBuffer.length() < bufferSize) {
            if (charSupplier.hasNext()) {
                valueBuffer.append(charSupplier.next());
            }
            else {
                reachedEnd = true;
            }
        }
    }
    
    private boolean expectString(final String expected) throws CharSupplierException {
        ensureBufferSize(expected.length());
        
        if (valueBuffer.length() >= expected.length()) {
            for (int index = 0; index < expected.length(); index++) {
                if (valueBuffer.charAt(index) != expected.charAt(index)) {
                    return false;
                }
            }
            
            return true;
        }
        else {
            return false;
        }
    }
    
    private String expectAnyString(final Collection<String> expectedStrings) throws CharSupplierException {
        for (final String expected : expectedStrings) {
            if (expectString(expected)) {
                return expected;
            }
        }
        
        return null;
    }
    
    private boolean expectAnyStringOrEnd(final Collection<String> expectedStrings) throws CharSupplierException {
        return !hasMoreUnprocessedChars() || expectAnyString(expectedStrings) != null;
    }
    
    private void consumeFromBuffer(final int amount) {
        valueBuffer.delete(0, amount);
        charIndex += amount;
    }
    
    private void consumeChar(final Consumer<? super CharSequence> consumer) {
        consumer.accept(Character.toString(valueBuffer.charAt(0)));
        consumeFromBuffer(1);
    }
    
    private boolean consumeIfExpected(final String expected) throws CharSupplierException {
        if (expectString(expected)) {
            consumeFromBuffer(expected.length());
            return true;
        }
        else {
            return false;
        }
    }
    
    private void consumeExpected(final String expected) throws ParseException, CharSupplierException {
        if (!consumeIfExpected(expected)) {
            throw new ParseException(this, String.format("Expected string '%s' was not found", expected));
        }
    }
    
    private boolean hasMoreUnprocessedChars() throws CharSupplierException {
        ensureBufferSize(1);
        return valueBuffer.length() > 0;
    }
    
    public boolean hasMore() throws CharSupplierException {
        return columnIndex == 0 || hasMoreUnprocessedChars();
    }
    
    public void nextRow() throws ParseException, CharSupplierException {
        if (columnIndex == 0) {
            throw new ParseException(this, "Have to consume empty value at row start first");
        }
        else {
            consumeExpected(lineBreakString);
            rowIndex++;
            columnIndex = 0;
        }
    }
    
    public boolean isNextNewRow() throws CharSupplierException {
        return expectString(lineBreakString);
    }
    
    public boolean isTrailingEmptyRow() throws CharSupplierException {
        return columnIndex == 0 && !hasMoreUnprocessedChars();
    }
    
    /**
     * Reads a value and passes the value pieces to the given consumer, which 
     * can for example be {@link StringBuilder#append(CharSequence)}. The consumer 
     * is not called when an empty value was read.
     * 
     * @param valuePieceConsumer
     *      Consumes the value pieces while they are parsed
     * @throws ParseException
     *      When the reader is currently not at a value start
     * @throws ParseException
     *      When the value is in an invalid format
     * @throws CharSupplierException
     *      When the char supplier failed supplying more characters
     * @see #isNextValue()
     */
    public void readValue(final Consumer<? super CharSequence> valuePieceConsumer) throws ParseException, CharSupplierException {
        if (columnIndex != 0) {
            consumeExpected(separatorString);
        }
        
        if (consumeIfExpected(quotationString)) {
            while (true) {
                if (consumeIfExpected(quotationString)) {
                    if (expectAnyStringOrEnd(unquotedValueEnds)) {
                        break;
                    }
                    else {
                        consumeExpected(quotationString);
                        valuePieceConsumer.accept(quotationString);
                    }
                }
                else if (hasMoreUnprocessedChars()) {
                    consumeChar(valuePieceConsumer);
                }
                else {
                    throw new ParseException(this, "Quoted value is missing closing quote");
                }
            }
        }
        else {
            while (!expectAnyStringOrEnd(unquotedValueEnds)) {
                if (expectString(quotationString)) {
                    throw new ParseException(this, "Found unexpected quotation mark");
                }
                else {
                    consumeChar(valuePieceConsumer);
                }
            }
        }
        
        columnIndex++;
    }
    
    public boolean isNextValue() throws CharSupplierException {
        return columnIndex == 0 || expectString(separatorString);
    }
    
    public void skipValue() throws ParseException, CharSupplierException {
        readValue(NO_OP_CONSUMER);
    }
    
    public DataType peekNext() throws CharSupplierException, ParseException {
        for (final DataType dataType : DataType.VALUES_LIST) {
            if (dataType.check(this)) {
                return dataType;
            }
        }
        
        throw new ParseException(this, "Malformed data");
    }
    
    /**
     * <p>Peeks at the next data using {@link #peekNext()} and if it is {@link DataType#ROW}, 
     * then already consumes the line break. This method should be preferred to the separate peeking 
     * and consuming methods when reading rows.</p>
     * 
     * <h1>Usage</h1>
     * <h2>No trailing empty line</h2>
     * <p>This treats no provided chars as a single empty value.</p>
     *<pre>
     *while (true) {
     *    DataType dataType = csvReader.peekOrConsumeNext(false);
     *    
     *    if (dataType.isRowFinished()) {
     *        // Handle row end
     *    }
     *    
     *    if (dataType == DataType.VALUE) {
     *        // Handle value
     *    }
     *    else if (dataType == DataType.END) {
     *        // Reached end, can break loop
     *        break;
     *    }
     *}
     *</pre>
     * 
     * <h2>Trailing empty line</h2>
     *<pre>
     *&#x2f;&#x2f; Data is empty
     *if (csvReader.isTrailingEmptyRow()) {
     *    return;
     *}
     *
     *while (true) {
     *    DataType dataType = csvReader.peekOrConsumeNext(true);
     *    
     *    if (dataType.isRowFinished()) {
     *        // Handle row end
     *    }
     *    
     *    if (dataType == DataType.VALUE) {
     *        // Handle value
     *    }
     *    else if (dataType == DataType.END) {
     *        // Reached end, can break loop
     *        break;
     *    }
     *}
     *</pre>
     * 
     * @param expectTrailingEmptyRow
     *      Whether an empty row is expected at the end of the chars
     * @return
     *      The type of the following data
     * @throws CharSupplierException
     *      When the char supplier failed supplying more characters
     * @throws ParseException
     *      When the data is malformed
     */
    public DataType peekOrConsumeNext(final boolean expectTrailingEmptyRow) throws CharSupplierException, ParseException {
        final DataType dataType = peekNext();
        
        if (dataType == DataType.ROW) {
            nextRow();
            
            if (expectTrailingEmptyRow && isTrailingEmptyRow()) {
                return DataType.END;
            }
        }
        
        return dataType;
    }
    
    public static CsvReader createCsvReader(final Reader reader, final int bufferCapacity) {
        return new CsvReader(new CharSupplier() {
            private final CharBuffer charBuffer;
            private Boolean hasNext;
            
            {
                charBuffer = CharBuffer.allocate(bufferCapacity);
                hasNext = null;
            }
            
            private void readNext() throws CharSupplierException {
                charBuffer.clear();
                final int readChars;
                
                try {
                    readChars = reader.read(charBuffer);
                }
                catch (final IOException ioException) {
                    throw new CharSupplierException(ioException);
                }
                
                if (readChars == -1) {
                    hasNext = false;
                }
                else {
                    hasNext = true;
                    charBuffer.flip();
                }
            }
            
            @Override
            public CharSequence next() throws CharSupplierException, IllegalStateException {
                if (hasNext == null) {
                    readNext();
                }
                
                if (!hasNext) {
                    throw new IllegalStateException("Supplier has no next chars");
                }
                
                hasNext = null;
                return charBuffer;
            }
            
            @Override
            public boolean hasNext() throws CharSupplierException {
                if (hasNext == null) {
                    readNext();
                }
                
                return hasNext;
            }
        });
    }
    
    // TODO: Remove
    public static void main(String[] args) throws CharSupplierException, ParseException {
        final CsvReader reader = new CsvReader(new CharSupplier() {
            private boolean hasSupplied = false;
            
            @Override
            public boolean hasNext() {
                return !hasSupplied;
            }

            @Override
            public CharSequence next() throws CharSupplierException {
                hasSupplied = true;
                return "";
            }
        });
        System.out.println(reader.isNextValue());
        reader.readValue(new StringBuilder()::append);
    }
}
