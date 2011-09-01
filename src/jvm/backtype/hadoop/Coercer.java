package backtype.hadoop;

import backtype.hadoop.FileCopyInputFormat.FileCopyArgs;
import backtype.hadoop.formats.RecordInputStream;
import backtype.hadoop.formats.RecordOutputStream;
import backtype.hadoop.formats.RecordStreamFactory;
import backtype.support.Utils;
import java.io.IOException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.lib.NullOutputFormat;


public class Coercer {
    private static final String FACTIN_ARG = "coercer_stream_factin_arg";
    private static final String FACTOUT_ARG = "coercer_stream_factout_arg";

    public static void coerce(String source, String dest, int renameMode, PathLister lister, RecordStreamFactory factin, RecordStreamFactory factout) throws IOException {
        coerce(source, dest, renameMode, lister, factin, factout, "");
    }

    public static void coerce(String qualSource, String qualDest, int renameMode, PathLister lister, RecordStreamFactory factin, RecordStreamFactory factout, String extensionOnRename) throws IOException {
        if(!Utils.hasScheme(qualSource) || !Utils.hasScheme(qualDest))
            throw new IllegalArgumentException("source and dest must have schemes " + qualSource + " " + qualDest);

        
        FileCopyArgs args = new FileCopyArgs(qualSource, qualDest, renameMode, lister, extensionOnRename);
        JobConf conf = new JobConf(Coercer.class);
        Utils.setObject(conf, FileCopyInputFormat.ARGS, args);
        Utils.setObject(conf, FACTIN_ARG, factin);
        Utils.setObject(conf, FACTOUT_ARG, factout);

        conf.setJobName("Coercer: " + qualSource + " -> " + qualDest);

        conf.setInputFormat(FileCopyInputFormat.class);
        conf.setOutputFormat(NullOutputFormat.class);
        conf.setMapperClass(CoercerMapper.class);

        conf.setSpeculativeExecution(false);

        conf.setNumReduceTasks(0);

        conf.setOutputKeyClass(NullWritable.class);
        conf.setOutputValueClass(NullWritable.class);

        RunningJob job = null;
        try {
            job = new JobClient(conf).submitJob(conf);
            while(!job.isComplete()) {
                Thread.sleep(100);
            }
            
            if(!job.isSuccessful()) throw new IOException("Coercer failed");
        } catch(IOException e) {
            if (job!=null) job.killJob();
            IOException ret = new IOException("Coercer failed");
            ret.initCause(e);
            throw ret;
        } catch(InterruptedException e) {
            job.killJob();
            throw new RuntimeException(e);
        }
    }


    public static class CoercerMapper extends AbstractFileCopyMapper {

        RecordStreamFactory factin;
        RecordStreamFactory factout;

        @Override
        protected void copyFile(FileSystem fsSource, Path source, FileSystem fsDest, Path target, Reporter reporter) throws IOException {
            RecordInputStream fin = factin.getInputStream(fsSource, source);
            RecordOutputStream fout = factout.getOutputStream(fsDest, target);

            try {
                byte[] record;
                int bytes = 0;
                while((record = fin.readRawRecord()) != null) {
                    fout.writeRaw(record);
                    bytes+=record.length;
                    if(bytes >= 1000000) { //every 1 MB of data report progress so we don't time out on large files
                        bytes = 0;
                        reporter.progress();
                    }
                }
            } finally {
                fin.close();
            }
            //don't complete files that aren't done yet. prevents partial files from being written
            fout.close();
        }

        @Override
        public void configure(JobConf job) {
            super.configure(job);
            factin = (RecordStreamFactory) Utils.getObject(job, FACTIN_ARG);
            factout = (RecordStreamFactory) Utils.getObject(job, FACTOUT_ARG);
        }
    }
}