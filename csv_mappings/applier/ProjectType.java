package csv_mappings.applier;

public enum ProjectType {
    CLIENT() {
        @Override
        public boolean applies(final int side) {
            return side == 0 || side == 2;
        }    
    },
    SERVER() {
        @Override
        public boolean applies(final int side) {
            return side == 1 || side == 2;
        }    
    },
    JOINED() {
        @Override
        public boolean applies(final int side) {
            return side >= 0 && side <= 2;
        }    
    }
    ;
    
    public abstract boolean applies(int side);
}
