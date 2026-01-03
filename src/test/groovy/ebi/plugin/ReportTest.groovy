package ebi.plugin

import spock.lang.Specification

class ReportTest extends Specification {

    def "test asset reading functionality"() {
        when:
        def jsAssets = []
        def cssAssets = []

        // Test reading JavaScript assets
        jsAssets.add(Report.readAsset("assets/nf-metalog_report.js"))
        
        // Test reading CSS assets  
        cssAssets.add(Report.readAsset("assets/nf-metalog_report.css"))

        then:
        // Verify we can read the assets
        jsAssets.size() == 1
        cssAssets.size() == 1
        
        // Verify the content is not empty
        jsAssets[0].size() > 0
        cssAssets[0].size() > 0
        
        // Verify the content contains expected patterns
        jsAssets[0].contains("nf-metalog")
        cssAssets[0].contains("metalog")
    }



    def "test template reading"() {
        when:
        def template = Report.readAsset("nf-metalog_report.html")

        then:
        template != null
        template.size() > 0
        template.contains("nf-metalog report")
    }
}