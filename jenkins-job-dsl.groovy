pipelineJob("SSL Certificates Monitoring Tool") {

    description("SSL Certificates Monitoring Tool.")
    
    logRotator {
        
        artifactDaysToKeep(0)
        artifactNumToKeep(0)
        daysToKeep(0)
        numToKeep(10)
    }

    parameters {

        stringParam( "JOB_NAME", "SSL CERTS EXPIRY DATE MONITORING TOOL")
        
        stringParam( "JOB_GIT_REPOSITORY", "git@github.com:graadi/ssl-certificates-monitor-jenkins-pipeline.git")
        stringParam( "JOB_GIT_BRANCH", "main")
        
        stringParam( "JOB_EMAIL_RECIPIENTS", "graadi@example.com" )
        stringParam( "JOB_BUILD_EMAIL_RECIPIENTS", "graadi@example.com" )
        
        activeChoiceParam('REPORT_FREQUENCY') {
            description('')
            filterable()
            choiceType('SINGLE_SELECT')
            groovyScript {
                script('return ["0":"NONE","7":"WEEKLY","14":"FORTHNIGHT","28":"MONTHLY:selected"]')
                fallbackScript('')
            }
        }

        booleanParam('RESET_BASE_DATE', false, '')
        booleanParam('RUN_AGGREGATE_REPORT', false, 'Tick this to force a run of the aggregated report.')

        stringParam( "GREEN_REPORT", "7", "The number of day that should trigger the first renewal warning. \"Green Report\".")
        stringParam( "ORANGE_REPORT", "5", "The number of day that should trigger the second renewal warning. \"Orange Report\".")
        stringParam( "RED_REPORT", "0", "The number of day that should trigger the last renewal warning. \"Red Report\". Default value is '0' which means in the day when the certificate expires.")
    }

    // Define the pipeline script which is located in Git
    definition {
        cpsScm {
            scm {
                git {
                    branch("master")
                    remote {
                        name("origin")
                        url("git@github.com:graadi/ssl-certificates-monitor-jenkins-pipeline.git")
                    }
                }
            }
        // The path within source control to the pipeline jobs Jenkins file
        scriptPath("jenkins-pipeline.groovy")
        }
    }
}