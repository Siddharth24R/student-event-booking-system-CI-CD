pipeline {
    agent any

    environment {
        IMAGE_NAME     = 'student-event-booking'
        IMAGE_TAG      = "${env.BUILD_NUMBER}"
        GCP_PROJECT_ID = credentials('GCP_PROJECT_ID')
    }

    tools {
        maven 'Maven 3.9'
        jdk   'Java 11'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                echo "Checked out branch: ${env.BRANCH_NAME}"
            }
        }

        stage('Build') {
            steps {
                echo 'Building with Maven...'
                sh 'mvn clean package -DskipTests'
            }
            post {
                success { echo 'Build succeeded.' }
                failure { echo 'Build failed.' }
            }
        }

        stage('Test') {
            steps {
                echo 'Running unit tests...'
                sh 'mvn test'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Docker Build') {
            steps {
                echo "Building Docker image: ${IMAGE_NAME}:${IMAGE_TAG}"
                sh "docker build -t gcr.io/${GCP_PROJECT_ID}/${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag gcr.io/${GCP_PROJECT_ID}/${IMAGE_NAME}:${IMAGE_TAG} gcr.io/${GCP_PROJECT_ID}/${IMAGE_NAME}:latest"
            }
        }

        stage('Push to GCR') {
            steps {
                echo 'Pushing image to Google Container Registry...'
                sh "docker push gcr.io/${GCP_PROJECT_ID}/${IMAGE_NAME}:${IMAGE_TAG}"
                sh "docker push gcr.io/${GCP_PROJECT_ID}/${IMAGE_NAME}:latest"
            }
        }

        stage('Deploy to Cloud Run') {
            steps {
                echo 'Deploying to GCP Cloud Run...'
                sh """
                    gcloud run deploy ${IMAGE_NAME} \
                        --image gcr.io/${GCP_PROJECT_ID}/${IMAGE_NAME}:${IMAGE_TAG} \
                        --platform managed \
                        --region europe-west2 \
                        --allow-unauthenticated \
                        --port 8080 \
                        --memory 512Mi
                """
            }
        }
    }

    post {
        success {
            echo "Pipeline succeeded — build #${env.BUILD_NUMBER} deployed successfully."
        }
        failure {
            echo "Pipeline failed — build #${env.BUILD_NUMBER}. Check logs above."
        }
        always {
            cleanWs()
        }
    }
}
