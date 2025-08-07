.PHONY: help

help: ## Show all commands
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

setup-dev: ## Setup development environment
	docker-compose -f docker-infra.yml up -d

down-dev: ## Down development environment
	docker-compose -f docker-infra.yml down -v

build: ## Build jar
	./gradlew clean build

run-leader: ## Run exchange leader
	./gradlew :exchange-app:run-leader

run-follower: ## Run exchange follower
	./gradlew :exchange-app:run-follower

run-learner: ## Run exchange learner
	./gradlew :exchange-app:run-learner

run-admin: ## Run admin client
	./gradlew :exchange-client-admin:run-admin

run-user: ## Run user client
	./gradlew :exchange-client-user:run-user

run-benchmark-tool: ## Run benchmark tool
	./gradlew :benchmark:benchmark-cluster:run-benchmark-tool
