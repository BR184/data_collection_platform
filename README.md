# QA Flex Platform

Standard separated project structure:

- `backend/`: Java 21 + Spring Boot
- `frontend/`: Vue 3 + Element Plus
- `tools/`: local Java and Maven toolchain

## Start backend

```powershell
cd backend
$env:DATASOURCE_PASSWORD="your-local-password"
$env:GITLAB_WEB_BASE_URL="http://your-gitlab-host"
.\run-backend.ps1
```

Backend default URL:

- `http://localhost:18080/`

## Start frontend

```powershell
cd frontend
.\run-frontend.ps1
```

Frontend default URL:

- `http://localhost:18181/`

## Verify local environment

Use the project environment script instead of relying on a user PowerShell profile:

```powershell
.\scripts\verify-local.ps1
```

The verification script checks the local toolchain, compiles the backend, and runs the frontend typecheck.

For manual commands:

```powershell
. .\scripts\dev-env.ps1
cd backend
mvn -q -DskipTests compile

cd ..\frontend
npm.cmd run typecheck
```
