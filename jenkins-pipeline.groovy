// -------------------------------------------------------------- //
// Jenkins declarative pipeline for                               //
// SSL Certificates Expiry Date Monitoring                        //
//                                                                //
// Author: Adrian Gramada                                         //
// Date:    November 2020                                         //
// -------------------------------------------------------------- //

def SDF_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS"
def KEY_FILE_EXTENSION = "pwd"

def switchCases = []
def greenGroup  = []
def orangeGroup = []
def redGroup    = []
def aggregated  = []

def greenGroupMap   = [:]
def orangeGroupMap  = [:]
def redGroupMap     = [:]

def jobGitRepository
def jobGitRepositoryBranch

def reportFrequency
def resetBaseDate
def runAggregate

def counter = 0

pipeline {

    agent any

    options {
      skipStagesAfterUnstable()
    }

    stages {

        stage('Initial Workspace Cleanup') {
            steps {
                deleteDir()
            }
        }

        stage('Read Parameters') {
            steps {
                script {

                    jobGitRepository = "${JOB_GIT_REPOSITORY}"
                    jobGitRepositoryBranch = "${JOB_GIT_BRANCH}"

                    try {
                        reportFrequency = "${REPORT_FREQUENCY}"
                    } catch(err) {
                        reportFrequency = 28
                    } finally {
                        /*
                        Overcome a strange bug where the Active Choice Parameter is not properly read when the job runs periodically
                        */
                        if(!reportFrequency.isNumber()) {
                            reportFrequency = 28
                        }
                        reportFrequency = reportFrequency.toInteger()
                    }

                    try {
                        resetBaseDate = "${RESET_BASE_DATE}"
                    } catch (err) {
                        resetBaseDate = false
                    } finally {
                        resetBaseDate = resetBaseDate.toBoolean()
                    }

                    try {
                        runAggregate = "${RUN_AGGREGATE_REPORT}"
                    } catch (err) {
                        runAggregate = false
                    } finally {
                        runAggregate = runAggregate.toBoolean()
                    }

                    def greenReport
                    try {
                        greenReport = "${GREEN_REPORT}"
                    } catch(err) {
                        greenReport = 7
                    } finally {
                        greenReport = greenReport.toInteger()
                    }

                    def orangeReport
                    try {
                        orangeReport = "${ORANGE_REPORT}"
                    } catch(err) {
                        orangeReport = 3
                    } finally {
                        orangeReport = orangeReport.toInteger()
                    }

                    def redReport
                    try {
                        redReport = "${RED_REPORT}"
                    } catch(err) {
                        redReport = 0
                    } finally {
                        redReport = redReport.toInteger()
                    }

                    switchCases = [
                        week: greenReport,
                        three: orangeReport,
                        zero: redReport
                    ]
                }
            }
        }

        stage('Create service SCM folder') {
            steps {
                script {
                    sh 'mkdir ${WORKSPACE}/job_repo'
                }
            }
        }

        stage('Checkout service SCM') {

            steps {

                dir("${WORKSPACE}/job_repo") {

                    script {
                        git url: jobGitRepository, branch: jobGitRepositoryBranch
                    }

                    script {
                        sh 'git pull origin ' + jobGitRepositoryBranch
                    }
                }
            }
        }

        stage('Config Report Date Parameter') {

            when {
                expression {
                    resetBaseDate
                }
            }

            steps {

                script {

                    dir("${WORKSPACE}/job_repo") {

                        sh 'sudo chown -R jenkins:jenkins ${WORKSPACE}/job_repo/'

                        try {
                            sh 'rm -fr config/base-date.cfg'
                        } catch (err) {
                            print 'Error while trying to delete \'config/base-date.cfg\'. Reason: ' + err
                        }

                        if (!fileExists('config/base-date.cfg')) {

                            def baseDate = java.time.LocalDateTime.now()
                            writeFile file: "config/base-date.cfg", text: "${baseDate}"

                            sh 'git branch --set-upstream-to=origin/' + jobGitRepositoryBranch + ' ' + jobGitRepositoryBranch
                            sh 'git pull'
                            sh 'git add .'
                            sh 'git commit -m \'File containing the base date has been created. Value: ' + baseDate + '\''
                            sh 'git push origin ' + jobGitRepositoryBranch
                        }
                    }
                }
            }
        }

        stage('SSL Certificates Check') {

            steps {

                script {

                    dir("${WORKSPACE}/job_repo") {

                        def certificateStoreFiles= getCertificateStoreFiles("${WORKSPACE}/job_repo/certificate-stores");

                        certificateStoreFiles.each { certificateStoreFile ->

                            String type = certificateStoreFile.substring(certificateStoreFile.lastIndexOf(".") + 1).toUpperCase()
                            print 'File[' + (++counter) + '] - ' + certificateStoreFile

                            switch(type) {

                                case "DL":
                                    print 'Domains List File'

                                    def output = sh(returnStdout: true, script: '${WORKSPACE}/job_repo/ssl-certificates-monitor-bash-script.sh -i -S -f certificate-stores/' + certificateStoreFile)

                                    print '--------------- Domains List ---------------'
                                    print output
                                    print '--------------------------------------------'

                                    def jsonContent = readJSON file: 'domains-file-json-output.json'
                                    def domainsCertificatesArray = jsonContent.certificatesList

                                    domainsCertificatesArray.each { arrayEntry ->

                                        def daysToExpire = arrayEntry.daysToExpire.toInteger()
                                        addEntryToGroup(daysToExpire, arrayEntry, greenGroup, orangeGroup, redGroup, switchCases)
                                        aggregated.add(arrayEntry)
                                    }
                                    break

                                case "CRT":
                                    print 'Certificate File'
                                    def output = sh(returnStdout: true, script: '${WORKSPACE}/job_repo/ssl-certificates-monitor-bash-script.sh -c certificate-stores/' + certificateStoreFile + ' -t ' + type.toLowerCase())

                                    print '--------------- Certificate File ---------------'
                                    print output
                                    print '-------------------------------------------------'

                                    def jsonContent = readJSON file: 'keystore-file-json-output.json'
                                    def domainsCertificatesArray = jsonContent.certificatesList

                                    domainsCertificatesArray.each { arrayEntry ->

                                        def daysToExpire = arrayEntry.daysToExpire.toInteger()
                                        addEntryToGroup(daysToExpire, arrayEntry, greenGroup, orangeGroup, redGroup, switchCases)
                                        aggregated.add(arrayEntry)
                                    }
                                    break

                                case "P12":
                                    print 'PKCS#12 binary format files.'
                                    String keyFile = certificateStoreFile.substring(0, certificateStoreFile.lastIndexOf(".") + 1).concat(KEY_FILE_EXTENSION)
                                    def output = sh(returnStdout: true, script: '${WORKSPACE}/job_repo/ssl-certificates-monitor-bash-script.sh -c certificate-stores/' + certificateStoreFile + ' -t ' + type.toLowerCase() + ' -w certificate-stores/' + keyFile)
                                    
                                    print '------------ PKCS#12 binary format files -------------'
                                    print output
                                    print '------------------------------------------------------'

                                    def jsonContent = readJSON file: 'keystore-file-json-output.json'
                                    def domainsCertificatesArray = jsonContent.certificatesList

                                    domainsCertificatesArray.each { arrayEntry ->

                                        def daysToExpire = arrayEntry.daysToExpire.toInteger()
                                        addEntryToGroup(daysToExpire, arrayEntry, greenGroup, orangeGroup, redGroup, switchCases)
                                        aggregated.add(arrayEntry)
                                    }                                                                        
                                    break

                                case "JKS":
                                    print 'Certificate TrustStore. Yet to be implemented.'
                                    break
                                    
                                case "PWD":
                                    print 'No implementation required for this type of file.'                                    
                                    break

                                default:
                                    print 'No valid case has been found. Build will be marked as unstable.'
                                    currentBuild.result = 'UNSTABLE'
                                    break
                            }
                        }

                        buildHtmlTemplate(
                            buildGroupMap(greenGroup),
                            'html/green-template-parts/component.part.html',
                            'html/green-template-parts/component.list.part.html',
                            'html/green-group-template.html',
                            "green-group-template-email.html",
                            "SSL Certificates due to expire in " + switchCases.week + " days"
                        )

                        buildHtmlTemplate(
                            buildGroupMap(orangeGroup),
                            'html/orange-template-parts/component.part.html',
                            'html/orange-template-parts/component.list.part.html',
                            'html/orange-group-template.html',
                            "orange-group-template-email.html",
                            "SSL Certificates due to expire in " + switchCases.three + " days"
                        )

                        buildHtmlTemplate(
                            buildGroupMap(redGroup),
                            'html/red-template-parts/component.part.html',
                            'html/red-template-parts/component.list.part.html',
                            'html/red-group-template.html',
                            "red-group-template-email.html",
                            "Expired SSL Certificates"
                        )
                    }
                }
            }
        }

        stage('Build Aggregated Report') {

            steps {

                script {

                    dir("${WORKSPACE}/job_repo") {

                        sh 'sudo chown -R jenkins:jenkins ${WORKSPACE}/job_repo/'

                        if (fileExists('config/base-date.cfg')) {

                            def file = readFile 'config/base-date.cfg'
                            def line = file.readLines()[0]

                            def days = getDateDifferenceInDays(line, SDF_PATTERN)

                            if(runAggregate || (days >= reportFrequency && (days % reportFrequency == 0))) {

                                aggregatedReport(aggregated,
                                    'html/aggregate-report/template.html',
                                    'html/aggregate-report/part.html',
                                    'aggregate-email.html',
                                    'SSL Certificates Summary'
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    post {

        failure {
            script {
                sendBuildEmailNotification()
            }
        }

        unstable {
            script {
                sendBuildEmailNotification()
            }
        }

        aborted {
            script {
                sendBuildEmailNotification()
            }
        }

        cleanup {
            deleteDir()
        }
    }
}

@NonCPS
def getAllFiles(def rootPath) {

    def list = []

    for (subPath in rootPath.list()) {
        list << subPath.getName()
    }

    return list
}

def createFilePath(def path) {

    if (env['NODE_NAME'].equals("master")) {
        File localPath = new File(path)
        return new hudson.FilePath(localPath);
    } else {
        return new hudson.FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), path);
    }
}

def getCertificateStoreFiles(def storeFilePath) {

    def certificateStores = []

    getAllFiles(createFilePath(storeFilePath)).each {
        certificateStores.add(it)
    }

    return certificateStores
}

def getDateDifferenceInDays(dateAsString, formatPattern) {

    def now = Date.from(java.time.LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toInstant())
    def then = new java.text.SimpleDateFormat(formatPattern).parse(dateAsString)

    return groovy.time.TimeCategory.minus(now, then).days
}

def addEntryToGroup(days, groupItem, greenGroup, orangeGroup, redGroup, switchCases) {

    switch(days) {

        case switchCases.week:
            greenGroup.add(groupItem)
            break

        case switchCases.three:
            orangeGroup.add(groupItem)
            break

        case switchCases.zero:
            redGroup.add(groupItem)
            break
    }
}

def buildGroupMap(group) {

    def map = [:]

    group.each { item ->

        if(map.containsKey(item.serialNumber)) {

            map.get(item.serialNumber).hostNameList.add(item.hostName)

        } else {

            config = [
                serialNumber: item.serialNumber,
                commonName: item.commonName,
                issuer: item.issuer,
                status: item.status,
                expiryDate: item.expiryDate,
                daysToExpire: item.daysToExpire,
                hostNameList: [item.hostName]
            ]
            map.put(item.serialNumber, config)
        }
    }
    return map
}

def buildHtmlTemplate(groupMap, componentPartFile, componentListPartFile, templateFile, tempEmailFile, emailSubject) {

    if(!groupMap) { return }

    def htmlTemplateParts  = ''

    groupMap.eachWithIndex { entry, i ->

        def htmlTemplateComponentPart = readFile(file: componentPartFile)

        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.certificate.commonname.value}", entry.value.commonName)
        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.certificate.expirydate.value}", entry.value.expiryDate)
        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.certificate.serialnumber.value}", entry.value.serialNumber)
        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.certificate.issuer.value}", entry.value.issuer)
        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.certificate.status.value}", entry.value.status)
        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.certificate.days.value}", entry.value.daysToExpire)

        def htmlTemplateComponentParts = ''

        entry.value.hostNameList.each { mapEntryValueListItem ->

            def htmlTemplateComponentListPart = readFile(file: componentListPartFile)

            htmlTemplateComponentListPart = htmlTemplateComponentListPart.replace("{component.website.url.value}", mapEntryValueListItem)
            htmlTemplateComponentParts += htmlTemplateComponentListPart
        }

        htmlTemplateComponentPart = htmlTemplateComponentPart.replace("{component.websites.list.html.template}", htmlTemplateComponentParts)
        htmlTemplateParts += htmlTemplateComponentPart
    }

    def template = readFile(file: templateFile)
    template = template.replace("{component.html.part.template}", htmlTemplateParts)

    writeFile file: tempEmailFile, text: template, encoding: "UTF-8"
    sendEmailNotification(tempEmailFile, emailSubject)
}

def aggregatedReport(aggregated, htmlTemplateParam, htmlpartParam, tempEmailFile, subject) {

    aggregatedMap = [:]

    aggregated.each { listItem ->

        if(!aggregatedMap.containsKey(listItem.serialNumber)) {

            aggregatedMapValue = [
                serialNumber: listItem.serialNumber,
                commonName: listItem.commonName,
                issuer: listItem.issuer,
                status: listItem.status,
                expiryDate: listItem.expiryDate,
                daysToExpire: listItem.daysToExpire
            ]
            aggregatedMap.put(listItem.serialNumber, aggregatedMapValue)
        }
    }

    if(!aggregatedMap) { return }

    def htmlParts  = ''

    aggregatedMap.eachWithIndex { entry, i ->

        def htmlPart = readFile(file: htmlpartParam)

        htmlPart = htmlPart.replace("{component.certificate.commonname.value}", entry.value.commonName)
        htmlPart = htmlPart.replace("{component.certificate.serialnumber.value}", entry.value.serialNumber)
        htmlPart = htmlPart.replace("{component.certificate.issuer.value}", entry.value.issuer)
        htmlPart = htmlPart.replace("{component.certificate.status.value}", entry.value.status)
        htmlPart = htmlPart.replace("{component.certificate.expirydate.value}", entry.value.expiryDate)
        htmlPart = htmlPart.replace("{component.certificate.days.value}", entry.value.daysToExpire)

        htmlParts += htmlPart
    }

    def template = readFile(file: htmlTemplateParam)
    template = template.replace("{aggregate.html.part.template}", htmlParts)

    writeFile file: tempEmailFile, text: template, encoding: "UTF-8"
    sendEmailNotification(tempEmailFile, subject)
}

def sendEmailNotification(htmlTemplateFile, emailSubject) {

    emailext body: '${FILE,path="' + htmlTemplateFile + '"}',
            mimeType: 'text/html',
            subject: emailSubject,
            to: "${JOB_EMAIL_RECIPIENTS}",
            replyTo: "${JOB_EMAIL_RECIPIENTS}"
}

def sendBuildEmailNotification() {

    emailext attachLog: true,
            body: '''${SCRIPT, template="groovy-html.template"}''',
            mimeType: 'text/html',
            subject: "${JOB_NAME} - ${currentBuild.fullDisplayName}",
            to: "${JOB_BUILD_EMAIL_RECIPIENTS}",
            replyTo: "${JOB_BUILD_EMAIL_RECIPIENTS}"
}
