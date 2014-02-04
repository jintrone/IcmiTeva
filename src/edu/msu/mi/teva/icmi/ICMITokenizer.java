package edu.msu.mi.teva.icmi;

import edu.mit.cci.text.preprocessing.AlphaNumericTokenizer;
import edu.mit.cci.text.preprocessing.CompositeMunger;
import edu.mit.cci.text.preprocessing.Munger;

/**
 * Created by josh on 12/19/13.
 */
public class ICMITokenizer extends AlphaNumericTokenizer {
    public ICMITokenizer(Munger... mungers) {
       super(mungers);
    }

    public ICMITokenizer(){
        super();
    }

    public String replace(String input) {
        return super.replace(input.replace("_",""));
    }


}
