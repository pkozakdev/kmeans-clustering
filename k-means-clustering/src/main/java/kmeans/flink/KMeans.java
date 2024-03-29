package kmeans.flink;

import kmeans.datatype.Centroid;
import kmeans.datatype.Point;
import kmeans.util.UDFs;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.IterativeDataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.DataSetUtils;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.core.fs.FileSystem;


public class KMeans {
    public static void main(String[] args) throws Exception {
        // Checking input parameters
        final ParameterTool params = ParameterTool.fromArgs(args);
        if (!params.has("input"))
            throw new Exception("Input Data is not specified");
        if (!params.has("d"))
            throw new Exception("No of Dimensions is not specified");

        // set up execution environment
        ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        // make parameters available in the web interface
        env.getConfig().setGlobalJobParameters(params);

        // get the number of dimensions
        int d = params.getInt("d");

        // get the number of clusters
        int k = params.getInt("k", 2);

        // get the number of iterations;
        int maxIter = params.getInt("iterations", 10);

        // get the threshold for convergence
        double threshold = params.getDouble("threshold", 0);

        // check if convergence criteria is set
        boolean convergence = params.getBoolean("convergence", false);

        // get input data:
        DataSet<Point> points = env.readTextFile(params.get("input"))
                .map(new UDFs.PointData(d));

        DataSet<Centroid> centroids;

        // get the centres if specified
        // else, derive initial cluster centres randomly from input vectors
        if (params.has("centres")) {
            centroids = env.readTextFile(params.get("centres"))
                    .map(new UDFs.PointData(d))
                    .reduceGroup(new UDFs.CentroidLabeler());
        } else {
            centroids = DataSetUtils.sampleWithSize(points, false, k, Long.MAX_VALUE)
                    .reduceGroup(new UDFs.CentroidLabeler());
        }

        // Use Bulk iteration specifying max possible iterations
        // If the clusters converge before that, the iteration will stop.
        IterativeDataSet<Centroid> loop = centroids.iterate(maxIter);

        // Execution of the kMeans algorithm
        DataSet<Centroid> newCentroids = points
                // compute closest centroid for each point
                .map(new UDFs.SelectNearestCenter()).withBroadcastSet(loop, "centroids")
                // count and sum point coordinates for each centroid
                .map(new UDFs.CountAppender())
                // group by the centroid ID
                .groupBy(0)
                .reduce(new UDFs.CentroidAccumulator())
                // compute new centroids from point counts and coordinate sums
                .map(new UDFs.CentroidAverager());

        DataSet<Centroid> finalCentroids;

        if (convergence) {
            // Join the new centroid dataset with the previous centroids
            DataSet<Tuple2<Centroid, Centroid>> compareSet = newCentroids
                    .join(loop)
                    .where("id")
                    .equalTo("id");

            //Evaluate whether the cluster centres are converged (if so, return empy data set)
            DataSet<Centroid> terminationSet = compareSet
                    .flatMap(new UDFs.ConvergenceEvaluator(threshold));

            // feed new centroids back into next iteration
            // If all the clusters are converged, iteration will stop
            finalCentroids = loop.closeWith(newCentroids, terminationSet);
        } else {
            finalCentroids = loop.closeWith(newCentroids);
        }

        // assign points to final clusters
        DataSet<Tuple2<Integer, Point>> result = points
                .map(new UDFs.SelectNearestCenter()).withBroadcastSet(finalCentroids, "centroids");

        // format the results
        DataSet<String> formattedResult = result.map(new UDFs.ResultFormatter());

        // emit result
        if (params.has("output")) {
            //finalCentroids.writeAsCsv(params.get("output"), "\n", Constants.DELIMITER, FileSystem.WriteMode.OVERWRITE);
            formattedResult.writeAsText(params.get("output"), FileSystem.WriteMode.OVERWRITE);
            // since file sinks are lazy, we trigger the execution explicitly
            env.execute("kMeans Clustering");
        } else {
            System.out.println("Printing result to stdout. Use --output to specify output path.");
            //finalCentroids.print();
            formattedResult.print();
        }
    }
}
