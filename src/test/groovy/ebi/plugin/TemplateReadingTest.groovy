package ebi.plugin

import spock.lang.Specification

class TemplateReadingTest extends Specification {

    def "test readAsset method"() {
        when:
        def template = Report.readAsset("nf-metalog_report.html")

        then:
        template != null
        template.size() > 0
        template.contains("nf-metalog report")
        template.contains("<html")
        template.contains("</html")
    }

    def "test readAsset with assets"() {
        when:
        def cssContent = Report.readAsset("assets/nf-metalog_report.css")
        def jsContent = Report.readAsset("assets/nf-metalog_report.js")

        then:
        cssContent != null
        cssContent.size() > 0
        cssContent.contains("metalog")
        
        jsContent != null
        jsContent.size() > 0
        jsContent.contains("nf-metalog")
    }

    def "test readAsset with non-existent resource"() {
        when:
        Report.readAsset("non-existent-file.txt")

        then:
        thrown(FileNotFoundException)
    }
}