package kmeans.datatype;


public class Centroid extends Point {

    public int id;

    public Centroid() {
    }

    public Centroid(int id, double[] data) {
        super(data);
        this.id = id;
    }

    public Centroid(int id, Point p) {
        super(p.getFields());
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return id + " " + super.toString();
    }
}

