package com.beniregev.util.strings;

import com.beniregev.util.strings.threshold.RangeNodeThreshold;
import com.beniregev.util.strings.threshold.Thresholder;

import java.util.Arrays;

/**
 * <div>
 *     Matches leftmost longest matches. Useful when you want non-overlapping matches with a string set that doesn't have strings that are prefix to other strings in the set.
 * </div>
 * @author binyamin.regev
 * @since jdk-1.8.0_162
 */
public class LongestMatchSet implements StringSet {
    private boolean caseSensitive = true;
    private TrieNode root;

    public LongestMatchSet(final Iterable<String> keywords, boolean caseSensitive) {
        this(keywords, caseSensitive, new RangeNodeThreshold());
    }

    public LongestMatchSet(final Iterable<String> keywords, boolean caseSensitive, final Thresholder thresholdStrategy) {
        this.caseSensitive = caseSensitive;
        // Create the root node
        root = new HashmapNode(true, 0);
        // Add all keywords
        for (String keyword : keywords) {
            // Skip any empty keywords
            if (keyword != null && keyword.length() > 0) {
                // Start with the current node and traverse the tree
                // character by character. Add nodes as needed to
                // fill out the tree.
                HashmapNode currentNode = (HashmapNode) root;
                for (int idx = 0; idx < keyword.length(); idx++) {
                    currentNode = currentNode.getOrAddChild(caseSensitive ? keyword.charAt(idx) : Character.toLowerCase(keyword.charAt(idx)));
                }
                // Last node will contains the keyword as a match.
                // Suffix matches will be added later.
                currentNode.matchLength = keyword.length();
            }
        }
        // Go through nodes breadth first, swap any hashmap nodes,
        // whose size is close to the size of range of keys with
        // flat array based nodes.

        // Calculate fail transitions and add suffix matches to nodes.
        // A lot of these properties are defined in a recursive fashion i.e.
        // calculating for a 3 letter word requires having done the calculation
        // for all 2 letter words.
        //
        final Queue<TrieNode> queue = new Queue<TrieNode>();
        root = root.optimizeNode(0, thresholdStrategy);
        queue.push(root);
        queue.push(null);
        // Need to use array to get mutateable state for anonymous class
        final int[] level = new int[] { 1 };

        EntryVisitor failTransAndOutputsVisitor = new EntryVisitor() {

            public void visit(TrieNode parent, char key, TrieNode value) {
                // First optimize node
                value = value.optimizeNode(level[0], thresholdStrategy);
                parent.updateTransition(key, value);

                // Get fail transiton of the parent.
                TrieNode parentFail = parent.getFailTransition();
                // Since root node has null fail transition, first level nodes have null parentFail.
                if (parentFail == null) {
                    // First level nodes have one possible fail transition, which is
                    // root because the only possible suffix to a one character
                    // string is an empty string
                    value.failTransition = parent;
                } else {
                    // Dig up the tree until you find a fail transition.
                    do {
                        // Suffix of a parent + transition character from parent to this
                        // node is the suffix of this node.

                        // parent ---char---> value
                        // parentFail ----char----> valueFail
                        // e.g. "ab" -> c -> "abc"
                        // "b" -> c -> "bc"
                        final TrieNode matchContinuation = parentFail.getTransition(key);
                        if (matchContinuation != null) {
                            value.failTransition = matchContinuation;
                        } else {
                            // If parentFail didn't have key mapping
                            // take parentFail's failTransition and try again
                            // The last fail transition is the root node, which
                            // always has a key mapping.
                            parentFail = parentFail.getFailTransition();
                        }
                    } while (value.failTransition == null);
                    // Now that we have a fail transition, this node matches all
                    // the matches of it's failTransition node in addition to any
                    // match it already has.
                    // e.g for keywords "abc", "bc", "c", "b", the "abc" node matches
                    // "abc" and also its failure transtion's matches ("bc", "c")
                    // "ab" has no match of its own, but it matches failure transition's
                    // match "b".

                    // Fail transitions are basically a linked list, because of the recursive fashion in which
                    // they are defined. But only some of them have matches on them. We want to skip those
                    // that don't, that is why we have suffix match references, which form a similar linked
                    // list like fail transitions but they skip over those without matches. Since the suffix
                    // matches for shorter suffixes have been sorted out, it's only a matter of linking to the
                    // first fail transition with a match. But there is another thing we want. We want to
                    // avoid the case where a node is without a match but it has suffix matches, as that would
                    // introduce another if. That is why in case of nodes without matches we store the suffix
                    // match directly on the node and instead link the next suffix match as this node's suffix
                    // match.
                    TrieNode fail = value.failTransition;
                    while (fail != root && fail.matchLength == 0) {
                        fail = fail.failTransition;
                    }
                    if (fail.matchLength != 0) {
                        if (value.matchLength == 0) {
                            value.matchLength = fail.matchLength;
                            value.suffixMatch = fail.suffixMatch;
                        } else {
                            value.suffixMatch = fail;
                        }
                    }
                }
                // Queue the non-leaf node.
                if (!value.isEmpty()) {
                    queue.push(value);
                }
            }

        };
        while (!queue.isEmpty()) {
            TrieNode n = queue.take();
            if (n == null) {
                if (!queue.isEmpty()) {
                    queue.push(null);
                    level[0]++;
                }
            } else {
                n.mapEntries(failTransAndOutputsVisitor);
            }
        }

        // Range nodes represent a range of transitions without all the transitions in the range being
        // there. In case of hitting on an empty slot the logic in match loop runs down the fail transition
        // chain to find a node with a transition for that char. Instead of wasting space on empty slots
        // we can do that beforehand and add that transition to the node. We need to do that in depth first
        // fashion, otherwise an endless loop can form.
        EntryVisitor enqueueNodesVisitor = new EntryVisitor() {

            public void visit(TrieNode parent, char key, TrieNode value) {
                // go depth first
                if (!value.isEmpty()) {
                    queue.push(value);
                }
            }

        };
        root.mapEntries(enqueueNodesVisitor);
        while (!queue.isEmpty()) {
            TrieNode node = queue.pop();
            if (node == null) {
                node = queue.pop();
                if (node instanceof RangeNode) {
                    // Range nodes have gaps (null values) in their array. We can put this wasted
                    // memory to work by filling these gaps with the correct next node for that character
                    // which we can figure out by following failure transitions.
                    RangeNode rangeNode = (RangeNode) node;
                    for (int i = 0; i < rangeNode.size; i++) {
                        if (rangeNode.children[i] == null) {
                            char charOfMissingTransition = (char) (rangeNode.baseChar + i);
                            // Walk up fail transition until you run out of them (and do nothing)
                            // or one of them has a transition for this char. Put that node
                            // into the empty slot on the range node.
                            TrieNode n = rangeNode.failTransition;
                            while (n != null) {
                                TrieNode nextNode = n.getTransition(charOfMissingTransition);
                                if (nextNode == null) {
                                    n = n.failTransition;
                                } else {
                                    rangeNode.children[i] = nextNode;
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                queue.push(null);
                node.mapEntries(enqueueNodesVisitor);
            }
        }
    }

    public void match(final String haystack, final SetMatchListener listener) {

        // Start with the root node.
        TrieNode currentNode = root;
        SetMatchQueue queue = new SetMatchQueue();
        int idx = 0;
        // For each character.
        final int len = haystack.length();
        // Putting this if into the loop worsens the performance so we'll sadly
        // have to deal with duplicated code.
        if (caseSensitive) {
            while (idx < len) {
                final char c = haystack.charAt(idx);
                // Try to transition from the current node using the character
                TrieNode nextNode = currentNode.getTransition(c);

                // If cannot transition, follow the fail transition until finding
                // node X where you can transition to another node Y using this
                // character. Take the transition.
                boolean failTransition = false;
                while (nextNode == null) {
                    failTransition = true;
                    // Transition follow one fail transition
                    currentNode = currentNode.getFailTransition();
                    // See if you can transition to another node with this
                    // character. Note that root node will return itself for any
                    // missing transition.
                    nextNode = currentNode.getTransition(c);
                }
                // Take the transition.
                currentNode = nextNode;
                // Output any matches on the current node
                currentNode.output(queue, ++idx);
                // If fail transition was taken, we can flush the match queue.
                // We flush all matches that end before the start of the of the fail transition taken.
                if (failTransition && !queue.matchAndClear(haystack, listener, idx - currentNode.level)) {
                    return;
                }
            }
            // Flush the rest of the matches.
            queue.matchAndClear(haystack, listener, Integer.MAX_VALUE);
        } else {
            while (idx < len) {
                final char c = Character.toLowerCase(haystack.charAt(idx));
                // Try to transition from the current node using the character
                TrieNode nextNode = currentNode.getTransition(c);

                // If cannot transition, follow the fail transition until finding
                // node X where you can transition to another node Y using this
                // character. Take the transition.
                boolean failTransition = false;
                while (nextNode == null) {
                    failTransition = true;
                    // Transition follow one fail transition
                    currentNode = currentNode.getFailTransition();
                    // See if you can transition to another node with this
                    // character. Note that root node will return itself for any
                    // missing transition.
                    nextNode = currentNode.getTransition(c);
                }
                // Take the transition.
                currentNode = nextNode;
                // Output any matches on the current node
                currentNode.output(queue, ++idx);
                // If fail transition was taken, we can flush the match queue.
                // We flush all matches that end before the start of the of the fail transition taken.
                if (failTransition && !queue.matchAndClear(haystack, listener, idx - currentNode.level)) {
                    return;
                }
            }
            // Flush the rest of the matches.
            queue.matchAndClear(haystack, listener, Integer.MAX_VALUE);
        }
    }

    private interface EntryVisitor {
        void visit(TrieNode parent, char key, TrieNode value);
    }

    // An open addressing hashmap implementation with linear probing
    // and capacity of 2^n
    private final static class HashmapNode extends TrieNode {

        // Start with capacity of 1 and resize as needed.
        private TrieNode[] children = new TrieNode[1];
        private char[] keys = new char[1];
        // Since capacity is a power of 2, we calculate mod by just
        // bitwise AND with the right mask.
        private int modulusMask = keys.length - 1;
        private int numEntries = 0;

        protected HashmapNode(boolean root, int level) {
            super(root, level);
        }

        @Override
        public TrieNode getTransition(final char key) {
            int defaultSlot = hash(key) & modulusMask;
            int currentSlot = defaultSlot;
            // Linear probing to find the entry for key.
            do {
                if (keys[currentSlot] == key) {
                    return children[currentSlot];
                } else if (children[currentSlot] == null) {
                    return defaultTransition;
                } else {
                    currentSlot = ++currentSlot & modulusMask;
                }
            } while (currentSlot != defaultSlot);
            return defaultTransition;
        }

        @Override
        public boolean isEmpty() {
            return numEntries == 0;
        }

        @Override
        public void mapEntries(EntryVisitor visitor) {
            for (int i = 0; i < keys.length; i++) {
                if (children[i] != null) {
                    visitor.visit(this, keys[i], children[i]);
                }
            }
        }

        @Override
        public void updateTransition(char c, TrieNode node) {
            int defaultSlot = hash(c) & modulusMask;
            int currentSlot = defaultSlot;
            do {
                if (children[currentSlot] == null) {
                    throw new IllegalArgumentException("Transition for " + c + " doesn't exist.");
                } else if (keys[currentSlot] == c) {
                    children[currentSlot] = node;
                    return;
                } else {
                    currentSlot = ++currentSlot & modulusMask;
                }
            } while (currentSlot != defaultSlot);
            throw new IllegalArgumentException("Transition for " + c + " doesn't exist.");
        }

        @Override
        protected TrieNode optimizeNode(int level, Thresholder thresholdStrategy) {
            char minKey = '\uffff';
            char maxKey = 0;
            // Find you the min and max key on the node.
            int size = numEntries;
            for (int i = 0; i < children.length; i++) {
                if (children[i] != null) {
                    if (keys[i] > maxKey) {
                        maxKey = keys[i];
                    }
                    if (keys[i] < minKey) {
                        minKey = keys[i];
                    }
                }
            }
            // If difference between min and max key are small
            // or only slightly larger than number of entries, use a range node
            int keyIntervalSize = maxKey - minKey + 1;
            if (thresholdStrategy.isOverThreshold(size, level, keyIntervalSize)) {
                return new RangeNode(this, minKey, maxKey);
            } else {
                return this;
            }
        }

        // Double the capacity of the node, calculate the new mask,
        // rehash and reinsert the entries
        private void enlarge() {
            char[] biggerKeys = new char[keys.length * 2];
            TrieNode[] biggerChildren = new TrieNode[children.length * 2];
            int biggerMask = biggerKeys.length - 1;
            for (int i = 0; i < children.length; i++) {
                char key = keys[i];
                TrieNode node = children[i];
                if (node != null) {
                    int defaultSlot = hash(key) & biggerMask;
                    int currentSlot = defaultSlot;
                    do {
                        if (biggerChildren[currentSlot] == null) {
                            biggerKeys[currentSlot] = key;
                            biggerChildren[currentSlot] = node;
                            break;
                        } else if (biggerKeys[currentSlot] == key) {
                            throw new IllegalStateException();
                        } else {
                            currentSlot = ++currentSlot & biggerMask;
                        }
                    } while (currentSlot != defaultSlot);
                }
            }
            this.keys = biggerKeys;
            this.children = biggerChildren;
            this.modulusMask = biggerMask;
        }

        // Return the node for a key or create a new hashmap node for that key
        // and return that.
        private HashmapNode getOrAddChild(char key) {
            // Check if we need to resize. Capacity of 2^16 doesn't need to resize.
            // If capacity is <16 and arrays are full or capacity is >16 and
            // arrays are 90% full, resize
            if (keys.length < 0x10000 && ((numEntries >= keys.length) || (numEntries > 16 && (numEntries >= keys.length * 0.90f)))) {
                enlarge();
            }
            int defaultSlot = hash(key) & modulusMask;
            int currentSlot = defaultSlot;
            do {
                if (children[currentSlot] == null) {
                    keys[currentSlot] = key;
                    HashmapNode newChild = new HashmapNode(false, level + 1);
                    children[currentSlot] = newChild;
                    ++numEntries;
                    return newChild;
                } else if (keys[currentSlot] == key) {
                    return (HashmapNode) children[currentSlot];
                } else {
                    currentSlot = ++currentSlot & modulusMask;
                }
            } while (currentSlot != defaultSlot);
            throw new IllegalStateException();
        }

        // FNV-1a hash
        private int hash(char c) {
            // HASH_BASIS = 0x811c9dc5;
            final int HASH_PRIME = 16777619;
            return (((0x811c9dc5 ^ (c >> 8)) * HASH_PRIME) ^ (c & 0xff)) * HASH_PRIME;
        }

    }

    // This node is good at representing dense ranges of keys.
    // It has a single array of nodes and a base key value.
    // Child at array index 3 has key of baseChar + 3.
    private static final class RangeNode extends TrieNode {

        private char baseChar = 0;
        private TrieNode[] children;
        private int size = 0;

        private RangeNode(HashmapNode oldNode, char from, char to) {
            super(oldNode.defaultTransition != null, oldNode.level);
            // Value of the first character
            this.baseChar = from;
            this.size = to - from + 1;
            this.matchLength = oldNode.matchLength;
            // Avoid even allocating a children array if size is 0.
            if (size <= 0) {
                size = 0;
            } else {
                this.children = new TrieNode[size];
                // If original node is root node, prefill everything with yourself.
                if (oldNode.defaultTransition != null) {
                    Arrays.fill(children, this);
                }
                // Grab the children of the old node.
                for (int i = 0; i < oldNode.children.length; i++) {
                    if (oldNode.children[i] != null) {
                        children[oldNode.keys[i] - from] = oldNode.children[i];
                    }
                }
            }
        }

        @Override
        public TrieNode getTransition(char c) {
            // First check if the key is between max and min value.
            // Here we use the fact that char type is unsigned to figure it out
            // with a single condition.
            int idx = (char) (c - baseChar);
            if (idx < size) {
                return children[idx];
            }
            return defaultTransition;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public void mapEntries(EntryVisitor visitor) {
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    if (children[i] != null && children[i] != this) {
                        visitor.visit(this, (char) (baseChar + i), children[i]);
                    }
                }
            }
        }

        @Override
        public void updateTransition(char c, TrieNode node) {
            // First check if the key is between max and min value.
            // Here we use the fact that char type is unsigned to figure it out
            // with a single condition.
            int idx = (char) (c - baseChar);
            if (idx < size) {
                if (children[idx] != null) {
                    children[idx] = node;
                    return;
                } else {
                    throw new IllegalArgumentException("Transition for " + c + " doesn't exist.");
                }
            }
            throw new IllegalArgumentException("Transition for " + c + " doesn't exist.");
        }

    }

    // Basic node for both
    private static abstract class TrieNode {

        protected TrieNode defaultTransition = null;
        protected TrieNode failTransition;
        // Depth of the node
        protected int level = 0;
        protected int matchLength = 0;
        protected TrieNode suffixMatch;

        protected TrieNode(boolean root, int level) {
            this.defaultTransition = root ? this : null;
            this.level = level;
        }

        // Get fail transition
        public final TrieNode getFailTransition() {
            return failTransition;
        }

        // Get transition (root node returns something non-null for all characters - itself)
        public abstract TrieNode getTransition(char c);

        public abstract boolean isEmpty();

        public abstract void mapEntries(final EntryVisitor visitor);

        // Report matches at this node. Use at matching.
        public final void output(SetMatchQueue queue, int idx) {
            // Since idx is the last character in the match
            // position it past the match (to be consistent with conventions)

            // Since all matches at one node are overlapping suffix matches in descending
            // length, first match accepted into the queue means subsequent matches won't be,
            // so we return.
            boolean matchAccepted = false;
            if (matchLength != 0) {
                matchAccepted = queue.push(matchLength, idx);
                TrieNode suffixMatch = this.suffixMatch;
                while (suffixMatch != null && !matchAccepted) {
                    matchAccepted = queue.push(suffixMatch.matchLength, idx);
                    suffixMatch = suffixMatch.suffixMatch;
                }
            }
        }

        public abstract void updateTransition(char c, TrieNode node);

        protected TrieNode optimizeNode(int level, Thresholder thresholdStrategy) {
            return this;
        }
    }
}
