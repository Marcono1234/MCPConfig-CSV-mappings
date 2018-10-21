package csv_mappings.applier;

import java.util.Arrays;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.SourceVersion;

/**
 * Class which helps sanitizing mappings and documentation. The methods act according 
 * to the <a href="https://docs.oracle.com/javase/specs/jls/se8/html">Java 8 Language 
 * Specification</a>. When the documentation of a member mentions unicode escaping, it 
 * is referring to "§3.3. Unicode Escapes" of the specification.
 */
public final class MappingSanitizer {
    private static final String COMMENT_TO_REPLACE_GROUP_NAME = "toReplace";
    private static final Pattern COMMENT_TO_REPLACE_PATTERN;
    private static final String COMMENT_REPLACEMENT;
    
    static {
        final char toReplace = '/';
        
        final StringBuilder patternStringBuilder = new StringBuilder();
        patternStringBuilder.append(getCharacterRegex('*', false));
        patternStringBuilder.append("(?<");
        patternStringBuilder.append(COMMENT_TO_REPLACE_GROUP_NAME);
        patternStringBuilder.append('>');
        patternStringBuilder.append(getCharacterRegex(toReplace, false));
        patternStringBuilder.append(')');
        
        COMMENT_TO_REPLACE_PATTERN = Pattern.compile(patternStringBuilder.toString());
        
        COMMENT_REPLACEMENT = Matcher.quoteReplacement(String.format("&#x%x;", (int) toReplace));
    }
    
    private final static String UNICODE_CODE_POINT_GROUP_NAME = "codePoint";
    private final static Pattern UNICODE_ESCAPE_PATTERN;
    static {
        UNICODE_ESCAPE_PATTERN = Pattern.compile("\\\\u+(?<" + UNICODE_CODE_POINT_GROUP_NAME + ">\\p{XDigit}{4})");
    }
    
    private static class ReplacementData {
        private final int replacementStart;
        private final int replacementEnd;
        private final CharSequence replacement;
        
        public ReplacementData(final int replacementStart, final int replacementEnd, final CharSequence replacement) {
            this.replacementStart = replacementStart;
            this.replacementEnd = replacementEnd;
            this.replacement = replacement;
        }
        
        public int getReplacementStart() {
            return replacementStart;
        }
        
        public int getReplacementEnd() {
            return replacementEnd;
        }
        
        public CharSequence getReplacement() {
            return replacement;
        }
    }
    
    private static CharSequence replaceRegex(final CharSequence toTransform, final Pattern pattern, final Function<Matcher, ReplacementData> replacementDataProducer) {
        final StringBuilder transformed = new StringBuilder(toTransform.length());
        
        final Matcher matcher = pattern.matcher(toTransform);
        int previousMatchEnd = 0;
        
        while (matcher.find()) {
            final ReplacementData replacementData = replacementDataProducer.apply(matcher);
            transformed.append(toTransform.subSequence(previousMatchEnd, replacementData.getReplacementStart()));
            transformed.append(replacementData.getReplacement());
            previousMatchEnd = replacementData.getReplacementEnd();
        }
        
        // Append remaining piece behind last match
        transformed.append(toTransform.subSequence(previousMatchEnd, toTransform.length()));
        
        return transformed;
    }
    
    /**
     * <p>Escapes block comment content to prevent it from prematurely ending the comment. 
     * This method replaces characters which would cause this with their respective 
     * HTML character references. It tries to do this only where absolutely necessary, 
     * leaving as much as possible of the content unchanged.</p>
     * 
     * <p>This method works for both, normal block and documentation, comments. The content 
     * passed to this method should be the text within the block comment:
     *<pre>
     *&#x2f;**
     * * The content
     * *&#x2f;
     *</pre></p>
     * 
     * <p>Unicode escapes are considered.</p>
     * 
     * @param content
     *      The content of the comment to escape
     * @return The escaped content
     */
    public static String escapeCommentContent(final String content) {
        return replaceRegex(
            content,
            COMMENT_TO_REPLACE_PATTERN,
            matcher -> new ReplacementData(
                matcher.start(COMMENT_TO_REPLACE_GROUP_NAME),
                matcher.end(COMMENT_TO_REPLACE_GROUP_NAME),
                COMMENT_REPLACEMENT
            )
        ).toString();
    }
    
    /**
     * Creates a regex for matching the given character or its unicode escape. If 
     * {@code ignoreCase} is {@code true} the regex matches case insensitive.
     * 
     * @param character
     *      The character to create the regex for
     * @param ignoreCase
     *      Whether the regex should match case insensitive
     * @return The created regex
     */
    private static String getCharacterRegex(final char character, final boolean ignoreCase) {
        final String charRegex = Pattern.quote(String.valueOf(character));
        final String resultCharRegex;
        
        if (ignoreCase) {
            resultCharRegex = createCaseInsensitiveRegex(charRegex);
        }
        else {
            resultCharRegex = charRegex;
        }
        
        final StringBuilder stringBuilder = new StringBuilder("(?:");
        stringBuilder.append(resultCharRegex);
        stringBuilder.append('|');
        // Create unicode escape regex
        stringBuilder.append("\\\\u+");
        stringBuilder.append(createCaseInsensitiveRegex(String.format("%04x", (int) character)));
        
        stringBuilder.append(')');
        
        return stringBuilder.toString();
    }
    
    /**
     * Creates and returns a non-capturing groups which matches the given 
     * regex case insensitive.
     * 
     * @param regex
     *      The regex to wrap
     * @return The created group
     */
    private static String createCaseInsensitiveRegex(final String regex) {
        return "(?i:" + regex + ")";
    }
    
    /**
     * Unescapes unicode escapes of the given string.
     * 
     * @param escaped
     *      String which contains unicode escapes
     * @return The unescaped string
     */
    public static String unescapeUnicodeEscapes(final String escaped) {
        return replaceRegex(
            escaped,
            UNICODE_ESCAPE_PATTERN,
            matcher -> new ReplacementData(
                matcher.start(),
                matcher.end(),
                // Parse hex 16-bit unicode code point
                String.valueOf((char) Integer.parseInt(
                    matcher.group(UNICODE_CODE_POINT_GROUP_NAME),
                    16)
                )
            )
        ).toString();
    }
    
    /**
     * Returns whether the given name is a valid Java identifier according to 
     * {@link SourceVersion#isName(CharSequence)}. If {@code unescapeUnicodeEscapes} 
     * is {@code true}, unicode escapes are unescaped before checking the name.
     * 
     * @param name
     *      The name to check
     * @param unescapeUnicodeEscapes
     *      Whether unicode escapes should be unescaped before the name is checked
     * @return Whether the name is a valid indentifier
     */
    public static boolean isValidIdentifier(final String name, final boolean unescapeUnicodeEscapes) {
        final String nameToCheck;
        
        if (unescapeUnicodeEscapes) {
            nameToCheck = unescapeUnicodeEscapes(name);
        }
        else {
            nameToCheck = name;
        }
        
        return SourceVersion.isName(nameToCheck);
    }
    
    // TODO Remove
    public static void main(String[] args) {
        System.out.println(SourceVersion.isName("new"));
        System.out.println(SourceVersion.isName("newa"));
        System.out.println(isValidIdentifier("\\u006eewa", true));
        System.out.println(unescapeUnicodeEscapes("\\uuuu006eewa"));
        System.out.println(COMMENT_TO_REPLACE_PATTERN.pattern());
        
        Arrays.asList(
            "abc\u002a/",
            "abc* /",
            "/*abc",
            "*/*a",
            "abc*\\u002f",
            "abc*\\uuuu002f",
            "abc*\\u002F",
            "abc\\u002a\\u002f",
            "abc\\u002A\\u002F"
        ).forEach(testStr -> System.out.println(escapeCommentContent(testStr)));
    }
    
    private MappingSanitizer() { }
}
