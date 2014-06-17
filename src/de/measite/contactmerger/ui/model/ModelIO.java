package de.measite.contactmerger.ui.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class ModelIO {

    public static void store(ArrayList<MergeContact> model, File file)
            throws FileNotFoundException, IOException
    {
        Kryo kryo = new Kryo();
        register(kryo);
        Output output = new Output(
            new GZIPOutputStream(new FileOutputStream(file)));
        kryo.writeObject(output, model);
        output.flush();
        output.close();
    }

    public static ArrayList<MergeContact> load(File file)
            throws FileNotFoundException, IOException
    {
        try {
            Kryo kryo = new Kryo();
            register(kryo);
            Input input = new Input(new GZIPInputStream(new FileInputStream(file)));
            @SuppressWarnings("unchecked")
            ArrayList<MergeContact> model =
               kryo.readObject(input, ArrayList.class);
            input.close();
            return model;
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
        kryo.register(ArrayList.class);
        kryo.register(RootContact.class);
        kryo.register(MergeContact.class);
    }

}
