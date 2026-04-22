/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-7 - Ported from JD-Core
 *
 * Loop-detection guard used by the reducer when it walks successor chains
 * (e.g. in `aggregateConditionalBranches`). Records each (parent, child) edge
 * it has already walked in the current chain; if the same edge is seen again,
 * the reducer has fallen into a cycle and we bail out rather than infinite-loop.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.HashSet;

public class WatchDog {
    protected HashSet<Link> links = new HashSet<Link>();

    public void clear() { links.clear(); }

    public void check(BasicBlock parent, BasicBlock child) {
        if (!child.matchType(BasicBlock.GROUP_END)) {
            Link link = new Link(parent, child);
            if (links.contains(link)) {
                throw new RuntimeException("CFG watchdog: parent=" + parent + ", child=" + child);
            }
            links.add(link);
        }
    }

    protected static class Link {
        protected final int parentIndex;
        protected final int childIndex;

        public Link(BasicBlock parent, BasicBlock child) {
            this.parentIndex = parent.getIndex();
            this.childIndex = child.getIndex();
        }

        @Override
        public int hashCode() { return 4807589 + parentIndex + 31 * childIndex; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Link)) return false;
            Link other = (Link) o;
            return parentIndex == other.parentIndex && childIndex == other.childIndex;
        }
    }
}
