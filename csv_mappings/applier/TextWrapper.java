package csv_mappings.applier;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class TextWrapper {
    private final int maxLineLength;
    private final String linePrefix;
    private final String lineBreakToWrite;
    
    public TextWrapper(final int maxLineLength, final String linePrefix, final String lineBreakToWrite) {
        if (linePrefix.length() >= maxLineLength) {
            throw new IllegalArgumentException(String.format("Line prefix '%s' is >= max line length %d", linePrefix, maxLineLength));
        }
        
        this.maxLineLength = maxLineLength;
        this.linePrefix = linePrefix;
        this.lineBreakToWrite = lineBreakToWrite;
    }
    
    public String transform(final String input) {
        return transform(Arrays.asList(input.split("\n")));
    }
    
    public String transform(final List<String> inputLines) {
        final Deque<CharSequence> unprocessedLines = new LinkedList<>(inputLines);
        final int lengthSum = inputLines.parallelStream().collect(Collectors.summingInt(String::length));
        final StringBuilder transformedString = new StringBuilder(lengthSum);
        
        while (!unprocessedLines.isEmpty()) {
            final StringBuilder line = new StringBuilder(linePrefix).append(unprocessedLines.removeFirst());
            final int lineLength = line.length();
            
            if (lineLength > maxLineLength) {
                CharSequence lineRest = null;
                
                // Try to find wrapping character in front of maxLineLength
                // Have 'linePrefix.length() - 1' as restriction to not accidentally wrap prefix
                for (int index = maxLineLength - 1; index > linePrefix.length() - 1; index--) {
                    if (canWrapAt(line.charAt(index))) {
                        // + 1 to include wrapping character in same line
                        lineRest = splitSuffix(line, index + 1);
                        break;
                    }
                }
                
                if (lineRest == null) {
                    /*
                     * Could not find wrapping character in front of maxLineLenght, try to 
                     * find next one after it at least
                     */
                    for (int index = maxLineLength; index < lineLength; index++) {
                        /*
                         * 'index < line.length() - 1' because then lineRest would be empty and 
                         * there would be no point in splitting
                         */
                        if (canWrapAt(line.charAt(index)) && index < lineLength - 1) {
                         // + 1 to include wrapping character in same line
                            lineRest = splitSuffix(line, index + 1);
                            break;
                        }
                    }
                }
                
                if (lineRest != null) {
                    unprocessedLines.addFirst(lineRest);
                }
            }
            
            transformedString.append(line);
            transformedString.append(lineBreakToWrite);
        }
        
        // Remove last new line
        transformedString.delete(transformedString.length() - lineBreakToWrite.length(), transformedString.length());
        return transformedString.toString();
    }
    
    protected boolean canWrapAt(final char character) {
        return character == ' ';
    }
    
    protected CharSequence splitSuffix(final StringBuilder stringBuilder, final int endIndex) {
        final CharSequence suffix = stringBuilder.subSequence(endIndex, stringBuilder.length());
        stringBuilder.delete(endIndex, stringBuilder.length());
        
        return suffix;
    }
}