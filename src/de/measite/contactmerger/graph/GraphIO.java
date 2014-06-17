package de.measite.contactmerger.graph;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import android.util.LongSparseArray;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class GraphIO {

    public static <N,E> void store(UndirectedGraph<N,E> g, File file)
            throws FileNotFoundException, IOException
    {
        Kryo kryo = new Kryo();
        register(kryo);
        Output output = new Output(
            new GZIPOutputStream(new FileOutputStream(file)));
        kryo.writeObject(output, g);
        output.flush();
        output.close();
    }

    public static <N,E> UndirectedGraph<N,E> load(File file)
            throws FileNotFoundException, IOException
    {
        try {
            Kryo kryo = new Kryo();
            register(kryo);
            Input input = new Input(new GZIPInputStream(new FileInputStream(file)));
            @SuppressWarnings("unchecked")
            UndirectedGraph<N,E> graph =
               kryo.readObject(input, UndirectedGraph.class);
            input.close();
            return graph;
        } catch (FileNotFoundException f) {
            throw f;
        } catch (IOException ioe) {
            try { file.delete(); } catch (Exception ex) {}
            throw ioe;
        } catch (Exception e) {
            try { file.delete(); } catch (Exception ex) {}
            throw new IOException("Deserialize failed", e);
        }
    }

    public static void register(Kryo kryo) {
        kryo.register(UndirectedGraph.class);
        kryo.register(LongSparseArray.class);
        kryo.register(HashMap.class);
    }

}
