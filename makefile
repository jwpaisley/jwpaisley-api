# Variables
MAIN_CLASS=com.jwpaisley.Main
SERVICE_NAME=javalin-api
PROJECT_ID=symbolic-card-237119
REGION=us-central1
DB_INSTANCE_NAME=jwpaisley

.PHONY: build run deploy help

## build: Clean and compile the Java project
build:
	mvn clean compile

## run: Build and run the Javalin server locally using local env vars
run: build
	DB_URL=$(NEON_DB_URL) DB_USER=$(NEON_DB_USER) DB_PASS=$(NEON_DB_PASS) JWT_SECRET=$(JWT_SECRET) TWILIO_ACCOUNT_SID=$(TWILIO_ACCOUNT_SID) TWILIO_AUTH_TOKEN=$(TWILIO_AUTH_TOKEN) TWILIO_FROM_NUMBER=$(TWILIO_FROM_NUMBER) API_SPORTS_API_KEY=$(API_SPORTS_API_KEY) \
	mvn exec:java -Dexec.mainClass='$(MAIN_CLASS)'

## deploy: Deploy to Cloud Run and set environment variables from your local shell
deploy:
	gcloud run deploy $(SERVICE_NAME) \
		--source . \
		--region $(REGION) \
		--set-env-vars="DB_URL=$(NEON_DB_URL),DB_USER=$(NEON_DB_USER),DB_PASS=$(NEON_DB_PASS),JWT_SECRET=$(JWT_SECRET),TWILIO_ACCOUNT_SID=$(TWILIO_ACCOUNT_SID),TWILIO_AUTH_TOKEN=$(TWILIO_AUTH_TOKEN),TWILIO_FROM_NUMBER=$(TWILIO_FROM_NUMBER),API_SPORTS_API_KEY=$(API_SPORTS_API_KEY),API_SPORTS_API_KEY=$(API_SPORTS_API_KEY)"

## help: Show available commands
help:
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@fgrep -h "##" $(MAKEFILE_LIST) | fgrep -v fgrep | sed -e 's/\\$$//' | sed -e 's/##//'