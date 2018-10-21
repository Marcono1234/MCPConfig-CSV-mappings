package csv_mappings.downloader;

import java.util.function.IntPredicate;
import java.util.function.Supplier;

// TODO: Remove?
public class ComparableEntry<K extends Comparable<K>, V> implements Comparable<ComparableEntry<K, V>> {
    @FunctionalInterface
    protected static interface ComparisonFunction<K extends Comparable<K>, V> {
        ComparableEntry<K, V> getCompared(ComparableEntry<K, V> existing, K newKey, Supplier<V> newValue);
    }
    
    private final K key;
    private final V value;
    
    public ComparableEntry(final K key, final V value) {
        this.key = key;
        this.value = value;
    }
    
    public K getKey() {
        return key;
    }
    
    public V getValue() {
        return value;
    }
    
    @Override
    public int compareTo(final ComparableEntry<K, V> other) {
        return key.compareTo(other.key);
    }
    
    /**
     * <p>Called when {@link #getCompared(K, Supplier, IntPredicate)} returns a new 
     * entry for the other data. This method is intended to clear the key and value 
     * of this entry if necessary.</p>
     * 
     * <p>This implementation does nothing, subclasses should override it if necessary.</p>
     */
    protected void onDiscarded() { }
    
    /**
     * <p>Compares the key of this entry with the given other key. If they are the same, this 
     * entry is returned, otherwise the given predicate is used to determine whether, 
     * based on the result, this entry should be returned. If not, first {@link #onDiscarded()} 
     * is called and then a new entry is created using the given key and the value supplier 
     * and is afterwards returned.</p>
     * 
     * <p>The following describes this in list form:</br>
     * Compare this key with other key
     * <ul>
     *  <li>{@code 0}: Return this</li>
     *  <li>{@code !0}: Use given predicate
     *      <ul>
     *          <li>{@code true}: Return this</li>
     *          <li>{@code false}:
     *              <ol>
     *                  <li>Call {@link #onDiscarded()} for this</li>
     *                  <li>Create and return new entry for other key and value supplier</li>
     *              </ol>
     *          </li>
     *      </ul>
     *  </li>
     * </ul>
     * </p>
     * 
     * @param otherKey
     *      The key of the other, not yet created, entry
     * @param otherValueSupplier
     *      Supplier for the value of the other, not yet created, entry
     * @param resultPredicate
     *      Predicate to use when this key and other key are not equal
     * @return
     *      This entry or a new entry created with the other data
     */
    private ComparableEntry<K, V> getCompared(final K otherKey, final Supplier<V> otherValueSupplier, final IntPredicate resultPredicate) {
        final int comparisonResult = key.compareTo(otherKey);
        
        if (comparisonResult == 0 || resultPredicate.test(comparisonResult)) {
            return this;
        }
        else {
            onDiscarded();
            return new ComparableEntry<>(otherKey, otherValueSupplier.get());
        }
    }
    
    public ComparableEntry<K, V> min(final K otherKey, final Supplier<V> otherValueSupplier) {
        return getCompared(otherKey, otherValueSupplier, result -> result < 0);
    }
    
    public ComparableEntry<K, V> max(final K otherKey, final Supplier<V>  otherValueSupplier) {
        return getCompared(otherKey, otherValueSupplier, result -> result > 0);
    }
    
    private static <K extends Comparable<K>, V> ComparableEntry<K, V> getComparedExisting(final ComparableEntry<K, V> existing, final K newKey, final Supplier<V> newValueSupplier, final ComparisonFunction<K, V> comparisonFunction) {
        if (existing == null) {
            return new ComparableEntry<>(newKey, newValueSupplier.get());
        }
        else {
            return comparisonFunction.getCompared(existing, newKey, newValueSupplier);
        }
    }
    
    public static <K extends Comparable<K>, V> ComparableEntry<K, V> minExisting(final ComparableEntry<K, V> existing, final K newKey, final Supplier<V>  newValueSupplier) {
        return getComparedExisting(existing, newKey, newValueSupplier, ComparableEntry::min);
    }
    
    public static <K extends Comparable<K>, V> ComparableEntry<K, V> maxExisting(final ComparableEntry<K, V> existing, final K newKey, final Supplier<V> newValueSupplier) {
        return getComparedExisting(existing, newKey, newValueSupplier, ComparableEntry::max);
    }
}
