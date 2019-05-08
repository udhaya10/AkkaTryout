class Summed {

    private final Integer el;

    public Summed(Integer el) {
        this.el = el;
    }

    public Summed sum(Summed other) {
        return new Summed(this.el + other.el);
    }

    public String toString(){
        return this.el.toString();
    }
}