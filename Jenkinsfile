pipeline {
    agent any

    environment {
        IMAGE_NAME = 'student-event-booking'
        IMAGE_TAG  = "${env.BUILD_NUMBER}"
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
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
            }
        }

        stage('Deploy') {
            steps {
                echo 'Deploying container...'
                sh '''
                    docker stop student-event-booking || true
                    docker rm   student-event-booking || true
                    docker run -d \
                        --name student-event-booking \
                        -p 8080:8080 \
                        --env-file .env \
                        student-event-booking:latest
                '''
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
