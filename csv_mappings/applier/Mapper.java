package csv_mappings.applier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import csv_mappings.applier.MappingProvider.MappingWithDoc;

/**
 * <p>Class which modifies a given char sequence, replacing field, method and parameter 
 * references and declarations and adding field and method documentation. Afterwards the 
 * processed input is passed to the processed string consumer. The mappings and documentation 
 * are retrieved from the given {@link MappingProvider}.</p>
 * 
 * <p>Instances of this class may only be used for the source code of a single class. 
 * The method {@link #append(char[], int, int)} adds the given input to be mapped. When the 
 * end of the source code has been reached, the {@link #finish()} method has to be called. 
 * This makes sure that cached content is passed to the consumer.</p>
 * 
 * <h1>Class format requirements</h1>
 * <p>This class requires that the mapped class and its members and method parameters meet 
 * certain requirements.</br>
 * The following list contains most of them:
 * <ul>
 *  <li>Names of fields, methods and parameters have to be unique across all classes and 
 *      must not be part of any other element where it is not the declaration of or a 
 *      reference to the respective member or method parameter</li>
 *  <li>Indentation: 4 spaces or one tab ({@code \t})</li>
 *  <li>Naming:<ul>
 *      <li>Fields: {@code field_[0-9]+_[a-zA-Z_]+}</li>
 *      <li>Methods: {@code func_[0-9]+_[a-zA-Z_]+}</li>
 *      <li>Parameters: {@code p_[\w]+_\d+_}</li>
 *  </ul></li>
 * </ul>
 * </p>
 * 
 * @param <E>
 *      The type of exception the processed string consumer can throw
 */
public class Mapper<E extends Exception> {
    private static enum MappingType {
        FIELD(RegexData.FIELD_GROUP_NAME) {
            @Override
            protected void performInternal(final MappingProvider mappingProvider, final Matcher matcher, final StringBuilder stringBuilder) {
                replaceName(mappingProvider::getFieldMapping, matcher, stringBuilder);
            }
        },
        METHOD(RegexData.METHOD_GROUP_NAME) {
            @Override
            protected void performInternal(final MappingProvider mappingProvider, final Matcher matcher, final StringBuilder stringBuilder) {
                replaceName(mappingProvider::getMethodMapping, matcher, stringBuilder);
            }
        },
        PARAM(RegexData.PARAM_GROUP_NAME) {
            @Override
            protected void performInternal(final MappingProvider mappingProvider, final Matcher matcher, final StringBuilder stringBuilder) {
                replaceName(mappingProvider::getParamMapping, matcher, stringBuilder);
            }
        },
        FIELD_DECLARATION(RegexData.FIELD_DECLARATION_GROUP_NAME) {
            @Override
            protected void performInternal(final MappingProvider mappingProvider, final Matcher matcher, final StringBuilder stringBuilder) {
                replaceName(mappingProvider::getFieldMapping, matcher, stringBuilder);
                insertDocumentation(mappingProvider::getFieldDoc, matcher, stringBuilder);
            }
        },
        METHOD_DECLARATION(RegexData.METHOD_DECLARATION_GROUP_NAME) {
            @Override
            protected void performInternal(final MappingProvider mappingProvider, final Matcher matcher, final StringBuilder stringBuilder) {
                replaceName(mappingProvider::getMethodMapping, matcher, stringBuilder);
                insertDocumentation(mappingProvider::getMethodDoc, matcher, stringBuilder);
            }
        }
        ;
        
        /**
         * <p>List containing {@link #values()}.</p>
         * 
         * <p>This list is unmodifiable and exists for efficiency reasons since 
         * {@link #values()} has to create a new array every time.</p>
         */
        public static final List<MappingType> VALUES_LIST = Collections.unmodifiableList(Arrays.asList(values()));
        
        private final static class RegexData {
            static final Pattern PATTERN;
            private static final String FIELD_GROUP_NAME = "field";
            private static final String METHOD_GROUP_NAME = "method";
            private static final String PARAM_GROUP_NAME = "param";
            private static final String LINE_BREAK_GROUP_NAME = "lineBreak";
            private static final String INDENTATION_GROUP_NAME = "indent";
            private static final String FIELD_DECLARATION_GROUP_NAME = FIELD_GROUP_NAME + "Decl";
            private static final String METHOD_DECLARATION_GROUP_NAME = METHOD_GROUP_NAME + "Decl";
            
            static {
                final List<String> regexes = new ArrayList<>();
                final String fieldRegex = "field_[0-9]+_[a-zA-Z_]+";
                final String methodRegex = "func_[0-9]+_[a-zA-Z_]+";
                final String paramRegex = "p_[\\w]+_\\d+_";
                final String indentationRegex = " {4}|\\t";
                
                /*
                 * Don't add documentation for overridden methods, checked for fields as well, 
                 * but should not matter
                 */
                regexes.add(createRegexGroup("(?<!@Override)\\R", LINE_BREAK_GROUP_NAME)
                    + createRegexGroup(indentationRegex, INDENTATION_GROUP_NAME)
                    + "(?:[\\w$.\\[\\]]+ )*"
                    + createRegexNonCapturingEither(Arrays.asList(
                        createRegexGroup(fieldRegex, FIELD_DECLARATION_GROUP_NAME) + " *(?:=|;)",
                        createRegexGroup(methodRegex, METHOD_DECLARATION_GROUP_NAME) + "\\("
                    ))
                );
                regexes.add(createRegexGroup(fieldRegex, FIELD_GROUP_NAME));
                regexes.add(createRegexGroup(methodRegex, METHOD_GROUP_NAME));
                regexes.add(createRegexGroup(paramRegex, PARAM_GROUP_NAME));
                
                PATTERN = Pattern.compile(createRegexNonCapturingEither(regexes));
            }
            
            private static String createRegexGroup(final String regex, final String groupName) {
                return String.format("(?<%s>%s)", groupName, regex);
            }
            
            private static String createRegexNonCapturingEither(final List<String> regexes) {
               return "(?:" + String.join("|", regexes) + ")";
            }
        }
        
        private final String groupName;
        
        private MappingType(final String groupName) {
            this.groupName = groupName;
        }
        
        protected String getGroupName() {
            return groupName;
        }
        
        /**
         * <p>Tries to replace a match with its mapped equivalent. The given matcher must 
         * found a match before. If the match is not relevant to this mapping type, no 
         * modification is performed and {@code false} is returned. Otherwise the mapping 
         * for the match result is looked up in the mapping provider and the string builder 
         * is modified. The match operation was performed on a copy of the string builder, 
         * therefore all indices apply to it.</p>
         * 
         * <p>Modification of the string builder must not include or be behind the end 
         * index of the matcher.</p>
         * 
         * @param mappingProvider
         *      Provider for the mappings
         * @param matcher
         *      The match result
         * @param stringBuilder
         *      Copy of the string builder on which the match was performed and which should 
         *      be modified
         * @return
         *      Whether the match result is relevant to this mapping type
         */
        public boolean perform(final MappingProvider mappingProvider, final Matcher matcher, final StringBuilder stringBuilder) {
            if (matcher.group(groupName) == null) {
                return false;
            }
            else {
                performInternal(mappingProvider, matcher, stringBuilder);
                return true;
            }
        }
        
        protected abstract void performInternal(MappingProvider mappingProvider, Matcher matcher, StringBuilder stringBuilder);
        
        protected void replaceName(final Function<String, Optional<String>> newNameRetriever, final Matcher matcher, final StringBuilder stringBuilder) {
            final String groupName = getGroupName();
            
            newNameRetriever.apply(matcher.group(groupName))
                .ifPresent(newName -> stringBuilder.replace(
                    matcher.start(groupName),
                    matcher.end(groupName),
                    newName
                )
            );
        }
        
        protected void insertDocumentation(final Function<String, Optional<String>> docRetriever, final Matcher matcher, final StringBuilder stringBuilder) {
            docRetriever.apply(matcher.group(getGroupName())).ifPresent(documentation -> {
                final String lineBreakString = matcher.group(RegexData.LINE_BREAK_GROUP_NAME);
                final String indentationString = matcher.group(RegexData.INDENTATION_GROUP_NAME);
                final TextWrapper textWrapper = new TextWrapper(80, indentationString + " * ", lineBreakString);
                final StringBuilder docString = new StringBuilder(documentation.length());
                
                docString.append(lineBreakString + indentationString + "/**" + lineBreakString);
                docString.append(textWrapper.transform(documentation));
                docString.append(lineBreakString);
                docString.append(indentationString + " */");
                
                /*
                 * Can safely insert at start even though name has already been replaced, 
                 * because it was replaced somewhere >= start()
                 */
                stringBuilder.insert(matcher.start(), docString);
            });
        }
    }
    
    @FunctionalInterface
    public static interface ExceptionThrowingConsumer<T, E extends Exception> {
        void accept(T value) throws E;
    }
    
    private final Mapper.ExceptionThrowingConsumer<CharSequence, E> processedStringConsumer;
    private final MappingProvider mappingProvider;
    private StringBuilder unprocessedStringBuilder;
    /**
     * Whether match was found, but more data could either increase the 
     * length of the match or turn it into a negative match
     */
    private boolean hasPossibleMatch;
    private boolean hasFinished;
    
    public Mapper(final Mapper.ExceptionThrowingConsumer<CharSequence, E> processedStringConsumer, final MappingProvider mappingProvider) {
        this.processedStringConsumer = processedStringConsumer;
        this.mappingProvider = mappingProvider;
        unprocessedStringBuilder = null;
        hasPossibleMatch = false;
        hasFinished = false;
    }
    
    /**
     * Appends the data from the given buffer and processes it.
     * 
     * @param buffer
     *      The buffer containing the data
     * @param startIndex
     *      Start index of the data buffer
     * @param length
     *      Length of the data to process
     * @throws IllegalStateException
     *      When the mapper already finished, see {@link #finish()}
     * @throws E
     *      When the processed string consumer throws an exception
     */
    public void append(final char[] buffer, final int startIndex, final int length) throws IllegalStateException, E {
        if (hasFinished) {
            throw new IllegalStateException("Has already finished");
        }
        
        // Is set again if match is still possible
        hasPossibleMatch = false;
        
        if (unprocessedStringBuilder == null) {
            unprocessedStringBuilder = new StringBuilder(length - startIndex);
        }
        
        unprocessedStringBuilder.append(buffer, startIndex, length);
        /*
         * Would be unreliable for supplementary characters since their surrogate 
         * pairs could be split, but should not be a problem here since PATTERN 
         * does not contain any supplementary character
         */
        // Use empty string because input is set every iteration using reset() anyways
        final Matcher matcher = MappingType.RegexData.PATTERN.matcher("");
        
        /*
         * Have to call toString() for StringBuilder since otherwise doc insertion 
         * after name replacement would fail because indices changed
         */
        while (true) {
            matcher.reset(unprocessedStringBuilder.toString());
            final boolean foundMatch = matcher.find();
            final boolean hitEnd = matcher.hitEnd();
            
            if (foundMatch) {
                if (hitEnd || matcher.requireEnd()) {
                    hasPossibleMatch = true;
                    markProcessed(matcher.start());
                    // Match reached end, can break here instead of 'continue'
                    break;
                }
                else {
                    processMatch(matcher);
                }
            }
            else {
                /*
                 * Only if end was hit stringBuilder might be relevant when combined with 
                 * next read string 
                 */
                if (!hitEnd) {
                    processedStringConsumer.accept(unprocessedStringBuilder);
                    unprocessedStringBuilder = null;
                }
                
                break;
            }
        }
    }
    
    protected void processMatch(final Matcher matcher) throws E {
        final int matchEndOffsetFromEnd = unprocessedStringBuilder.length() - matcher.end();
        
        for (final MappingType mappingType : MappingType.VALUES_LIST) {
            final boolean applied = mappingType.perform(mappingProvider, matcher, unprocessedStringBuilder);
            
            if (applied) {
                break;
            }
        }
        
        // Everything up to, excluding, end index was processed
        final int endIndex = unprocessedStringBuilder.length() - matchEndOffsetFromEnd;
        markProcessed(endIndex);
    }
    
    protected void markProcessed(final int upToIndex) throws E {
        processedStringConsumer.accept(unprocessedStringBuilder.subSequence(0, upToIndex));
        unprocessedStringBuilder.delete(0, upToIndex);
    }
    
    public void finish() throws E {
        hasFinished = true;
        
        if (unprocessedStringBuilder != null) {
            if (hasPossibleMatch) {
                final Matcher matcher = MappingType.RegexData.PATTERN.matcher(unprocessedStringBuilder.toString());
                matcher.find();
                processMatch(matcher);
            }
            
            processedStringConsumer.accept(unprocessedStringBuilder);
            unprocessedStringBuilder = null;
        }
    }
    
    // TODO Remove
    public static void main(String[] args) {
        System.out.println(MappingType.RegexData.PATTERN.pattern());
        System.out.println(MappingType.RegexData.PATTERN.matcher("    a$a[]. field_1_a =").matches());
        System.out.println(MappingType.RegexData.PATTERN.matcher("    a$a[]. func_1_a(").matches());
        System.out.println(MappingType.RegexData.PATTERN.matcher(" func_1_a").find());
        System.out.println(MappingType.RegexData.PATTERN.matcher("  field_1_a =").find());
        System.out.println(MappingType.RegexData.PATTERN.matcher("  p_a_1_").find());
        
        final String fieldName = "field_1_a";
        final StringBuilder stringBuilder = new StringBuilder("text\r\n    a$a[]. " + fieldName + ";");
        final Matcher matcher = MappingType.RegexData.PATTERN.matcher(stringBuilder.toString());
        matcher.find();
        
        final Map<String, MappingWithDoc> fieldMappings = new HashMap<>();
        fieldMappings.put(fieldName, new MappingWithDoc("newName", "Returns the minimal value of enchantability needed on the enchantment level passed."));
        final MappingProvider mappingProvider = new MappingProvider(fieldMappings, Collections.emptyMap(), Collections.emptyMap());
        
        for (final MappingType mappingType : MappingType.VALUES_LIST ) {
            final boolean applies = mappingType.perform(mappingProvider, matcher, stringBuilder);
            
            if (applies) {
                System.out.println("Applied: " + mappingType);
                break;
            }
        }
        
        System.out.println(stringBuilder.toString());
    }
}