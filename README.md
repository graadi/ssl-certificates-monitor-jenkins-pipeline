## Jenkins declarative pipeline to run the SSL Certificates Expiry Date Monitoring Tool

The pipeline script is in charge of executing the Bash script, to identify the expiring certificates, as well as generating the reports and firing the email alerts. The corresponding Jenkins job is parameterized, as following:

| Parameter Name             | Description                                                  |
| -------------------------- | ------------------------------------------------------------ |
| JOB_NAME                   | Arbitrary name that will be used as the subject for the job build notification email. |
| JOB_GIT_REPOSITORY         | Git repository of the monitoring tool project                |
| JOB_GIT_BRANCH             | Git branch that will be used to checkout the codebase from GitLab |
| JOB_EMAIL_RECIPIENTS       | The recipients list that will receive the alert emails containing the details of the certificates that are due to expire. |
| JOB_BUILD_EMAIL_RECIPIENTS | The recipients that will receive the email of the Jenkins build, in case of failures |
| REPORT_FREQUENCY           | Frequency, in days, of the aggregated report                 |
| GREEN_REPORT               | Frequency, in days, of the first alert, when certificates are due to expire |
| ORANGE_REPORT              | Frequency, in days, of the second alert, when certificates are due to expire |
| RED_REPORT                 | Frequency, in days, of the third alert, currently this is setup to run in the day when a certificate is due to expire. |

This pipeline has introduced a number of new concepts such as the usage of Map objects as well as the search and replace manipulation of the html report template files with values extracted from the SSL certificate details. The pipeline stages are executed as following:

<img src="https://github.com/graadi/ssl-certificates-monitor-jenkins-pipeline/blob/main/images/jenkins-stages.png" />

The "Config Report Date Parameter" stage will either create or modify a ```datestamp``` variable that will be used as the start date to calculate and decide whether the "Build Aggregated Report" stage will run or not. The aggregated report frequency is parameterized as well.
