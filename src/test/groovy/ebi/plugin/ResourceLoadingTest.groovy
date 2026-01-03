package ebi.plugin

import spock.lang.Specification

class ResourceLoadingTest extends Specification {

    def "test resource loading with different path formats"() {
        when:
        def testCases = [
            [path: "nf-metalog_report.html", shouldWork: true],
            [path: "/nf-metalog_report.html", shouldWork: true],
            [path: "assets/nf-metalog_report.js", shouldWork: true],
            [path: "/assets/nf-metalog_report.js", shouldWork: true],
            [path: "non-existent-file.txt", shouldWork: false]
        ]
        
        def results = testCases.collect { testCase ->
            try {
                def content = Report.readAsset(testCase.path)
                [path: testCase.path, success: true, contentSize: content?.size()]
            } catch (FileNotFoundException e) {
                [path: testCase.path, success: false, error: e.message]
            } catch (Exception e) {
                [path: testCase.path, success: false, error: "Unexpected error: ${e.message}"]
            }
        }
        
        then:
        // Verify expected results
        results.each { result ->
            def expectedResult = testCases.find { it.path == result.path }
            if (expectedResult.shouldWork) {
                assert result.success == true : "Expected ${result.path} to load successfully but got: ${result.error}"
                assert result.contentSize > 0 : "Expected ${result.path} to have content"
            } else {
                assert result.success == false : "Expected ${result.path} to fail but it succeeded"
            }
        }
        
        // Print debug information
        println "\n=== Resource Loading Test Results ==="
        results.each { result ->
            if (result.success) {
                println "✓ ${result.path} - ${result.contentSize} characters"
            } else {
                println "✗ ${result.path} - ${result.error}"
            }
        }
        println "==================================="
    }

    def "test all required assets are accessible"() {
        when:
        def requiredAssets = [
            "nf-metalog_report.html",
            "assets/plotly-basic-3.3.1.min.js",
            "assets/bootstrap.bundle.min.js",
            "assets/nf-metalog_report.js",
            "assets/bootstrap.min.css",
            "assets/datatables.min.js",
            "assets/datatables.min.css",
            "assets/nf-metalog_report.css"
        ]
        
        def loadedAssets = requiredAssets.collect { assetPath ->
            try {
                def content = Report.readAsset(assetPath)
                [path: assetPath, loaded: true, size: content.size()]
            } catch (Exception e) {
                [path: assetPath, loaded: false, error: e.message]
            }
        }
        
        then:
        // All assets should load successfully
        loadedAssets.every { it.loaded } == true
        
        // Print debug information
        println "\n=== Required Assets Status ==="
        loadedAssets.each { asset ->
            if (asset.loaded) {
                println "✓ ${asset.path} - ${asset.size} characters"
            } else {
                println "✗ ${asset.path} - ${asset.error}"
            }
        }
        println "==================================="
    }
}