package eu.amidst.core.datastream.filereaders.arffFileReader;

import eu.amidst.core.datastream.Attribute;
import eu.amidst.core.datastream.Attributes;
import eu.amidst.core.datastream.DataStream;
import eu.amidst.core.datastream.DataInstance;
import eu.amidst.core.datastream.filereaders.DataFileWriter;
import eu.amidst.core.variables.*;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * TODO Write a quickARFFReader and quickARFFSaver
 * Created by andresmasegosa on 23/02/15.
 */
public class ARFFDataWriter implements DataFileWriter {

    @Override
    public String getFileExtension() {
        return "arff";
    }

    public static void writeToARFFFile(DataStream<? extends DataInstance> dataStream, String path) throws IOException {
        FileWriter fw = new FileWriter(path);
        fw.write("@relation dataset\n\n");

        for (Attribute att : dataStream.getAttributes()){
            fw.write(ARFFDataWriter.attributeToARFFString(att)+"\n");
        }

        fw.write("\n\n@data\n\n");


        dataStream.stream().forEach(e -> {
            try {
                fw.write(ARFFDataWriter.dataInstanceToARFFString(dataStream.getAttributes(), e) + "\n");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });

        fw.close();
    }


    @Override
    public void writeToFile(DataStream<? extends DataInstance> dataStream, String path) throws IOException {
       ARFFDataWriter.writeToARFFFile(dataStream, path);
    }


    public static String attributeToARFFString(Attribute att){
        if (att.getStateSpace().getStateSpaceType()== StateSpaceType.REAL) {
            return "@attribute " + att.getName() + " real";
        } else if (att.getStateSpace().getStateSpaceType()== StateSpaceType.FINITE_SET) {
            StringBuilder stringBuilder = new StringBuilder("@attribute " + att.getName() + " {");
            FiniteStateSpace stateSpace = att.getStateSpace();
            stateSpace.getStatesNames().stream().limit(stateSpace.getNumberOfStates()-1).forEach(e -> stringBuilder.append(e+", "));
            stringBuilder.append(stateSpace.getStatesName(stateSpace.getNumberOfStates()-1)+"}");
            return stringBuilder.toString();
        }else{
            throw new IllegalArgumentException("Unknown SateSapaceType");
        }
    }

    public static String dataInstanceToARFFString(Attributes atts, DataInstance assignment){
        StringBuilder builder = new StringBuilder(atts.getNumberOfAttributes()*2);

        //MEJORAR PONER CUANDO REAL
        for(int i=0; i<atts.getNumberOfAttributes()-1;i++) {
            if (atts.getList().get(i).getStateSpace().getStateSpaceType() == StateSpaceType.FINITE_SET) {
                FiniteStateSpace stateSpace = atts.getList().get(i).getStateSpace();
                String nameState = stateSpace.getStatesName((int) assignment.getValue(atts.getList().get(i)));
                builder.append(nameState + ",");
            }else if (atts.getList().get(i).getStateSpace().getStateSpaceType() == StateSpaceType.REAL) {
                builder.append(assignment.getValue(atts.getList().get(i))+ ",");
            }else{
                throw new IllegalArgumentException("Illegal State Space Type: " + atts.getList().get(i).getStateSpace().getStateSpaceType());
            }
        }

        if(atts.getList().get(atts.getNumberOfAttributes()-1).getStateSpace().getStateSpaceType()  == StateSpaceType.FINITE_SET) {
            FiniteStateSpace stateSpace = atts.getList().get(atts.getNumberOfAttributes() - 1).getStateSpace();
            String nameState = stateSpace.getStatesName((int) assignment.getValue(atts.getList().get(atts.getNumberOfAttributes() - 1)));
            builder.append(nameState);
        }else if(atts.getList().get(atts.getNumberOfAttributes()-1).getStateSpace().getStateSpaceType()  == StateSpaceType.REAL) {
            builder.append(assignment.getValue(atts.getList().get(atts.getNumberOfAttributes() - 1)));
        }else{
            throw new IllegalArgumentException("Illegal State Space Type: " + atts.getList().get(atts.getNumberOfAttributes()-1).getStateSpace().getStateSpaceType());
        }
        return builder.toString();
    }

}