package eu.amidst.huginlink;

import COM.hugin.HAPI.Domain;
import COM.hugin.HAPI.ExceptionHugin;
import eu.amidst.core.models.BayesianNetwork;

/**
 * Created by afa on 11/12/14.
 */
public class BayesianNetworkWriter {

    public static void saveToHuginFile(BayesianNetwork amidstBN, String file) throws ExceptionHugin {
        Domain huginBN = BNConverterToHugin.convertToHugin(amidstBN);
        huginBN.saveAsNet(file);
    }
}
