package edu.msu.mi.teva.icmi;

import edu.mit.cci.teva.DefaultTevaFactory;
import edu.mit.cci.teva.cpm.cos.CosCommunityFinder;
import edu.mit.cci.teva.engine.*;
import edu.mit.cci.teva.model.Conversation;
import edu.mit.cci.teva.util.ExhaustiveAssignment;
import edu.mit.cci.text.preprocessing.*;
import edu.mit.cci.text.windowing.SingleThreadTokenWidthWindowingStrategy;
import edu.mit.cci.text.windowing.WindowStrategy;
import edu.mit.cci.text.windowing.Windowable;
import org.apache.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by josh on 12/19/13.
 */
public class ICMITevaFactory extends DefaultTevaFactory {

    private static Logger log = Logger.getLogger(ICMITevaFactory.class);

    public ICMITevaFactory(TevaParameters params, Conversation conversation) {
        super(params, conversation);
    }


    public Munger[] getMungers() throws IOException {
        List<Munger> mungers = new ArrayList<Munger>();
        if (params.getReplacementDictionary() != null && !params.getReplacementDictionary().isEmpty()) {
            if (params.getReplacementDictionary().startsWith("/") || params.getReplacementDictionary().startsWith(".")) {
                mungers.add(DictionaryMunger.read(new FileInputStream(params.getReplacementDictionary())));
                log.info("Loaded replacement list from file: " + params.getReplacementDictionary());
            } else {
                mungers.add(DictionaryMunger.read(getClass().getResourceAsStream("/" + params.getReplacementDictionary())));
                log.info("Loaded replacement list from resource: " + params.getReplacementDictionary());
            }
        }
        if (params.getStopwordList() != null && !params.getStopwordList().isEmpty()) {
            if (params.getStopwordList().startsWith("/") || params.getStopwordList().startsWith(".")) {
                mungers.add(StopwordMunger.readAndAdd(new FileInputStream(params.getStopwordList())));
                log.info("Loaded stopword list from file: " + params.getStopwordList());
            } else {
                mungers.add(StopwordMunger.readAndAdd(getClass().getResourceAsStream(("/" + params.getStopwordList()))));
                log.info("Loaded stopword list from resource: " + params.getStopwordList());
            }
        }
        return mungers.toArray(new Munger[mungers.size()]);
    }

    public CommunityMembershipStrategy getMembershipMatchingStrategy() {
        return new ExhaustiveAssignment();
    }

    public CommunityFinder getFinder() {
        return new CosCommunityFinder(params);
    }

    public TopicMembershipEngine getMembershipEngine(CommunityModel model, Conversation conversation) {
        return new NumTokensMembershipEngine(model, conversation, this, (int) params.getWindowSize());
    }

    public WindowStrategy.Factory<Windowable> getTopicWindowingFactory() {
        return new WindowStrategy.Factory<Windowable>() {

            final List<Windowable> posts = new ArrayList<Windowable>();

            {
                try {
                    for (List<Windowable> w : getConversationData()) {
                        posts.addAll(w);

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
                Collections.sort(posts, new Comparator<Windowable>() {
                    @Override
                    public int compare(Windowable o1, Windowable o2) {
                        return o1.getStart().compareTo(o2.getStart());
                    }
                });
            }

            public WindowStrategy<Windowable> getStrategy() {

                return new SingleThreadTokenWidthWindowingStrategy(posts, (int) params.getWindowSize(), (int) params.getWindowDelta());
            }
        };
    }


    public Tokenizer<String> getTokenizer() throws IOException {
        return new ICMITokenizer(getMungers());
    }
}
