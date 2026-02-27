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
	DB_URL=$(DB_URL) DB_USER=$(DB_USER) DB_PASS=$(DB_PASS) \
	mvn exec:java -Dexec.mainClass='$(MAIN_CLASS)'

## deploy: Deploy to Cloud Run and set environment variables from your local shell
deploy:
	gcloud run deploy $(SERVICE_NAME) \
		--source . \
		--region $(REGION) \
		--set-env-vars="DB_URL=$(CLOUDRUN_DB_URL),DB_USER=$(DB_USER),DB_PASS=$(DB_PASS)" \
		--add-cloudsql-instances=$(PROJECT_ID):$(REGION):$(DB_INSTANCE)

## help: Show available commands
help:
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@fgrep -h "##" $(MAKEFILE_LIST) | fgrep -v fgrep | sed -e 's/\\$$//' | sed -e 's/##//'