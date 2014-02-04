package edu.msu.mi.teva.icmi

import com.csvreader.CsvReader
import edu.mit.cci.adapters.csv.CsvBasedConversation
import edu.mit.cci.sna.Edge
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
import groovy.io.FileType
import org.apache.commons.math3.analysis.interpolation.LoessInterpolator

import javax.swing.JFileChooser
import java.awt.FileDialog

/**
 * Created by josh on 1/20/14.
 */
class ICMITester {


    ICMITester(File dir) {
        def process = [:]
        dir.eachFileMatch(FileType.FILES, ~/B.*(?:csv|ref)/) { f ->
            def prefix = (f.getName() =~ /([^\.]+)/)[0][0]
            if (!process[prefix]) {
                process[prefix] = [:]
            }
            if (f.getName().endsWith("ref")) {
                process[prefix]["ref"] = f
            } else {
                process[prefix]["dat"] = f
            }
        }

        process.keySet().retainAll(process.findResults { (it.value["ref"] && it.value["dat"]) ? it.key : null })
        run(process)
    }


    def run(Map data) {
        File out = new File("TestOutput.csv")
        out.withWriterAppend {
           it.println("corpus,window,delta,pmiss,pfa,pk")
        }
        data.each { String k, v ->
            runInstance(k, v["dat"] as File, v["ref"] as File,out)
        }
    }

    def params = [[window: 50, delta: 10], [window: 100, delta: 10], [window: 100, delta: 20], [window: 120, delta: 20], [window: 120, delta: 30]]

    static File createWorkingDirectory(File base, String name, int i) {
        File n = new File(base, "$name.$i")
        if (!n.exists()) {
            n.mkdir()
        } else {
            U.cleanDirectory(n)
        }
        n

    }

    def static Map assign(CommunityModel model) {
        Map result = new HashMap()

        for (Community c:model.communities)  {
            c.assignments.each { ConversationChunk chunk ->
                chunk.messages.each { Windowable w ->
                    if (!result[w.id]) {
                        result[w.id] = new Object[model.communities.size()]
                        Arrays.fill(result[w.id], 0f)
                    }
                    result[w.id][c.id as int] = chunk.coverage
                }
            }
        }
       result
    }

    def static smooth(Conversation conversation, Map assignments, smooth = 0.3d) {
        LoessInterpolator loess = new LoessInterpolator(smooth, 1)
        def x = []
        def length = assignments.values().first().length
        (0..<length).each { idx ->
            conversation.allThreads.each { t ->
                def data = t.posts.collect { p ->
                    if (x.size() < t.posts.size()) {
                        x << ((x && x.last() >= p.time.time) ? (x.last() + 1d) : (p.time.time as double))
                    }
                    assignments[p.postid] ? assignments[p.postid][idx] as double : 0d
                } as double[]
                data = loess.smooth(x as double[], data)
                t.posts.eachWithIndex { Post p, int i ->
                    if (assignments[p.postid]) assignments[p.postid][idx] = data[i]

                }
            }
        }
    }


    def static List segment(CommunityModel model, Conversation conversation) {

        def assignments = assign(model)
        smooth(conversation, assignments)

        def segs = []
        conversation.allThreads.each { t ->
            def lastid = -1
            t.posts.each { p ->
                if (assignments[p.postid]) {
                    def lidx = -1
                    assignments[p.postid].eachWithIndex { e, i ->
                        if (lidx < 0 || e > assignments[p.postid][lidx]) {
                            lidx = i
                        }
                    }
                    segs << ((lastid != lidx) ? 1 : 0)
                    lastid = lidx
                } else {
                    segs << 0
                }
            }
        }
        segs
    }


    def runInstance(String name, File data, File ref, File out) {
        Conversation c = loadConversation(data)
        List<Integer> segs = segmentationData(ref, c)
        TevaParameters tevaParams = new TevaParameters(System.getResourceAsStream("/icmi.teva.properties"));
        File base = new File(tevaParams.getWorkingDirectory())
        params.eachWithIndex { p, i ->
            File wd = createWorkingDirectory(base, name, i)
            new File(wd, "params.txt").withWriterAppend {
                it.println(p)
            }
            tevaParams.setWorkingDirectory(wd.absolutePath)
            tevaParams.setWindowSize(p.window)
            tevaParams.setWindowDelta(p.delta)
            CommunityModel model = new MemoryBasedRunner(c, tevaParams, new ICMITevaFactory(tevaParams, c)).process();
            TevaUtils.serialize(new File(tevaParams.getWorkingDirectory() + "/${c.getName()}.${tevaParams.getFilenameIdentifier()}.xml"), model, CommunityModel.class);
            def result = pk(segs, segment(model, c))
            out.withWriterAppend {
                it.println("${name},${p.window},${p.delta},${result.pMiss},${result.pFalseAlarm},${result.pk}")
            }


        }
    }


    static Conversation loadConversation(File file) {
        return new CsvBasedConversation(["id", "replyTo", "start", "author", "text"] as String[], file.getName(), file.newInputStream(), '\t' as char, false) {
            public Date processDate(CsvBasedConversation.Column field, CsvReader reader) {
                new Date(((processString(field, reader) as float) * 1000f) as long)
            }
        }
    }

    static List segmentationData(File f, Conversation c) {
        def segdata = []
        f.readLines().each { line ->
            def m = line =~ /([\d\.]+)/
            if (m.size() > 0) {
                segdata << Long.parseLong((m[0][0] as String).replaceAll(/\./, ""))
            }
        }
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
        ref
    }

    def static Map pk(List<Integer> ref, List<Integer> hyp, boundary = 1) {
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


        return [pMiss: probMiss, pFalseAlarm: probFalseAlarm, pk: probMiss * probDiffRef + probFalseAlarm * probSameRef]

    }

    static void test() {
        File f = U.getAnyFile("Load community file",".",JFileChooser.FILES_AND_DIRECTORIES)
        CommunityModel model = TevaUtils.getCommunityModelFromFile(f)
        assign(model)
    }


    static void main(String[] args) {
        File f = U.getAnyFile("Select a directory with data files", ".", JFileChooser.DIRECTORIES_ONLY)
        if (!f.isDirectory()) {
            println "File must be a directory"
        } else {
            new ICMITester(f)
        }

        //test()
    }


}
