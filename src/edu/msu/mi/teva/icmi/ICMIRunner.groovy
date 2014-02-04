package edu.msu.mi.teva.icmi

import com.csvreader.CsvReader
import edu.mit.cci.adapters.csv.CsvBasedConversation
import edu.mit.cci.sna.Edge
import edu.mit.cci.sna.NetworkUtils
import edu.mit.cci.teva.MemoryBasedRunner
import edu.mit.cci.teva.engine.Community
import edu.mit.cci.teva.engine.CommunityModel
import edu.mit.cci.teva.engine.ConversationChunk
import edu.mit.cci.teva.engine.TevaParameters
import edu.mit.cci.teva.model.Conversation
import edu.mit.cci.teva.model.DiscussionThread
import edu.mit.cci.teva.model.Post
import edu.mit.cci.teva.util.TevaUtils
import edu.mit.cci.text.windowing.Windowable
import edu.mit.cci.util.U
import org.apache.commons.math3.analysis.interpolation.LoessInterpolator;
import groovy.util.logging.Log4j


/**
 * Created by josh on 12/16/13.
 */
@Log4j
class ICMIRunner {

    Conversation conv

    public ICMIRunner(File file) {
        conv = new CsvBasedConversation(["id", "replyTo", "start", "author", "text"] as String[], file.getName(), file.newInputStream(), '\t' as char, false) {
            public Date processDate(CsvBasedConversation.Column field, CsvReader reader) {
                new Date(((processString(field, reader) as float) * 1000f) as long)
            }
        }

    }

    def static printInterWindowTopicOverlap(CommunityModel model, Conversation conversation) {
        def message_map = [:]
        def edge_map = [model: conversation]


        model.communities.each { Community c ->
            c.assignments.each { ConversationChunk chunk ->
                chunk.messages.each { Windowable w ->
                    if (!message_map[w.id]) {
                        message_map[w.id] = new Object[model.communities.size() + 1]
                        Arrays.fill(message_map[w.id], 0f)
                    }
                    message_map[w.id][c.id as int] = chunk.coverage

                }

            }

        }


        def name = "shift.${model.getCorpusName()}.q${model.getParameters().getFixedCliqueSize()}.x${model.getParameters().getMinimumLinkWeight()}.csv"



        new File(name).withWriter { Writer w ->
            conversation.allThreads.each { DiscussionThread t ->
                Post last = null;
                t.posts.each { Post p ->
                    def difference = 0
                    def weighteddiff = 0
                    if (last && message_map[last.postid]) {
                        def vals = (0..<model.communities.size()).collect {
                            def a = Math.abs(message_map[p.postid][it])
                            def b = Math.abs(message_map[last.postid][it])
                            [n: Math.abs(a - b), d: Math.max(a, b)]
                        }

                        //println "{$p.postid}. ${vals.findAll {it.d > 0}}"

                        difference = vals.n.sum() / (vals.d.sum() ?: 1)
                    } else {
                        // println "{$p.postid}. No prior post"

                    }
                    last = p
                    w.println "${p.time.time},${difference}"
                }

            }


        }
    }

    def static populateAssignments(CommunityModel model, Map message_map, Map total_coverage_map) {
        model.communities.each { Community c ->
            c.assignments.each { ConversationChunk chunk ->
                chunk.messages.each { Windowable w ->
                    if (!message_map[w.id]) {
                        message_map[w.id] = new Object[model.communities.size()]
                        Arrays.fill(message_map[w.id], 0f)
                        total_coverage_map[w.id] = [edges: [] as Set<Edge>, total: 0f]

                    }
                    message_map[w.id][c.id as int] = chunk.coverage
                    if (chunk.coverage > 0) {
                        total_coverage_map[w.id]["edges"] += (chunk.edges)
                        total_coverage_map[w.id]["total"] = (total_coverage_map[w.id]["edges"].size() as float) * (chunk.coverage / (chunk.edges.size() as float))
                    }
                }

            }

        }
    }

    def static List<Integer> printSmoothedOutput(CommunityModel model, Conversation conversation) {
        def message_map = [:]
        def total_coverage_map = [:]
        def x = []

        populateAssignments(model, message_map, total_coverage_map)
        model.communities.each { comm ->
            conversation.allThreads.each { t ->
                def data = t.posts.collect { p ->
                    if (x.size() < t.posts.size()) {
                        if (x && x.last() >= p.time.time) {
                            x << (x.last() + 1d)
                        } else {
                            x << (p.time.time as double)
                        }
                    }

                    message_map[p.postid] ? message_map[p.postid][comm.id as int] as double : 0d
                } as double[]

                data = loess.smooth(x as double[], data)

                t.posts.eachWithIndex { Post p, int i ->
                    if (message_map[p.postid]) message_map[p.postid][comm.id as int] = data[i]

                }

            }
        }
        def segs = []
        def breaks = []
        conversation.allThreads.each { t ->
            def lastid = -1

            t.posts.each { p ->
                if (message_map[p.postid]) {

                    def lidx = -1
                    message_map[p.postid].eachWithIndex { e, i ->

                        if (lidx < 0 || e > message_map[p.postid][lidx]) {
                            lidx = i
                        }
                    }
                    segs << ((lastid != lidx) ? 1 : 0)
                    if (lastid != lidx) {
                        breaks << p.time.time
                    }
                    lastid = lidx


                } else {
                    segs << 0
                }


            }
        }
        print("Guessed segs: " + breaks)

        def name = "${model.getCorpusName()}.q${model.getParameters().getFixedCliqueSize()}.x${model.getParameters().getMinimumLinkWeight()}.t${model.getParameters().getWordijIndirection()}.csv"

        new File(name).withWriter {
            Writer w ->
                w.println "time,${model.communities*.id.join(",")},totCov"
                conversation.allThreads.each { DiscussionThread t ->
                    Post last = null;
                    t.posts.each { Post p ->

                        w.println "${p.time.time},${message_map[p.postid] ? message_map[p.postid].join(",") : ""}, ${total_coverage_map[p.postid] ? total_coverage_map[p.postid]["total"] : ""}"
                    }

                }


        }

        return segs
    }

    def static float pk(List<Integer> ref, List<Integer> hyp, boundary = 1) {
        def k = Math.round(ref.size() / (ref.count(boundary) * 2)) as int
        println "K is " + k
        def nConsidered = ref.size() - k - 1
        def nSameRef = 0f
        def nFalseAlarm = 0f
        def nMiss = 0

        (0..nConsidered).each { i ->
            def bSameRefSeg = false
            def bSameHypSeg = false

            if (!(boundary in ref[(i + 1)..(i + k)])) {
                nSameRef += 1
                bSameRefSeg = true

            }
            if (!(boundary in hyp[(i + 1)..(i + k)])) {
                bSameHypSeg = true
            }

            if (!bSameRefSeg && bSameHypSeg) {
                nMiss += 1
            }
            if (bSameRefSeg && !bSameHypSeg) {
                nFalseAlarm += 1
            }


        }

        def probSameRef = nSameRef / nConsidered
        def probDiffRef = 1 - probSameRef
        def probMiss = nMiss / nConsidered
        def probFalseAlarm = nFalseAlarm / nConsidered

        probMiss * probDiffRef + probFalseAlarm * probSameRef


    }

    def static printSimpleOutput(CommunityModel model, Conversation conversation) {

        def message_map = [:]
        def total_coverage_map = [:]
        populateAssignments(model, message_map, total_coverage_map)
        def name = "smooth.${model.getCorpusName()}.q${model.getParameters().getFixedCliqueSize()}.x${model.getParameters().getMinimumLinkWeight()}.t${model.getParameters().getWordijIndirection()}.csv"

        new File(name).withWriter { Writer w ->
            w.println "time,${model.communities*.id.join(",")},totCov,diff,wDiff"
            conversation.allThreads.each { DiscussionThread t ->
                Post last = null;
                t.posts.each { Post p ->
                    def difference = 0
                    def weighteddiff = 0
                    if (last && message_map[last.postid] && message_map[p.postid]) {
                        def vals = (0..<model.communities.size()).collect {
                            def a = Math.abs(message_map[p.postid][it])
                            def b = Math.abs(message_map[last.postid][it])
                            [n: Math.abs(a - b), nw: Math.abs(a - b) * Math.max(a, b), d: Math.max(a, b)]
                        }

                        println "{$p.postid}. ${vals.findAll { it.d > 0 }}"

                        weighteddiff = 1 - (vals.nw.sum() / (vals.d.sum() ?: 1))
                        difference = 1 - (vals.n.sum() / (vals.d.sum() ?: 1))
                    } else {
                        println "{$p.postid}. No prior post"

                    }
                    last = p
                    w.println "${p.time.time},${message_map[p.postid] ? message_map[p.postid].join(",") : ""}, ${total_coverage_map[p.postid] ? total_coverage_map[p.postid]["total"] : ""},${difference},${weighteddiff}"
                }

            }


        }
    }

    public static void run() {

        File input = U.getAnyFile("Load ICMI file", ".", 0)


        Conversation c = new ICMIRunner(input).conv
        TevaParameters tevaParams = new TevaParameters(System.getResourceAsStream("/icmi.teva.properties"));

        1.each { xof ->
            U.cleanDirectory(new File(tevaParams.getWorkingDirectory()));
            //tevaParams.setMinimumLinkWeight(xof/100)
            CommunityModel model = new MemoryBasedRunner(c, tevaParams, new ICMITevaFactory(tevaParams, c)).process();
            TevaUtils.serialize(new File(tevaParams.getWorkingDirectory() + "/CommunityOutput." + c.getName() + "." + tevaParams.getFilenameIdentifier() + ".xml"), model, CommunityModel.class);
            printSimpleOutput(model, c)
            //printInterWindowTopicOverlap(model,c);
        }
    }

    public static void load() {
        File cfile = U.getAnyFile("Load ICMI file", ".", 0)
        Conversation c = new ICMIRunner(cfile).conv
        File input = U.getAnyFile("Load Community file", ".", 0)
        CommunityModel model = TevaUtils.getCommunityModelFromFile(input)
        File segs = U.getAnyFile("Load Segmentation", ".", 0)
        def segdata = []
        segs.readLines().each { line ->
            def m = line =~ /([\d\.]+)/
            if (m.size() > 0) {
                segdata << Long.parseLong((m[0][0] as String).replaceAll(/\./, ""))
            }
        }
        println("Reference data:" + segdata)
        List<Integer> ref = []
        c.allThreads.each { t ->
            def sidx = 0
            t.posts.each { p ->
                if (sidx < segdata.size() && p.time.time >= segdata[sidx]) {
                    sidx++
                    ref << 1
                } else {
                    ref << 0
                }
            }

        }
        def hyp = printSmoothedOutput(model, c)
        float pk = pk(ref, hyp)

        File out = new File("PK.csv")
        out.withWriterAppend { w ->
            (0..ref.size()).each {
                w.println "${ref[it]},${hyp[it]}"
            }
        }

        println("PK for ${model.corpusName}=${pk}")

        //printSimpleOutput(model, c)
        //printInterWindowTopicOverlap(model,c)


    }

    public static void main(String[] args) {
        run()
        //load()
    }


}
