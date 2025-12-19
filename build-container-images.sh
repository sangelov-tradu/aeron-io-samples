./echo "building admin image..."
docker build -f docker/Dockerfile --build-context gradle=./admin -t admin . --no-cache
echo "building cluster image..."
docker build -f docker/Dockerfile --build-context gradle=./cluster -t cluster . --no-cache
echo "building backup image..."
docker build -f docker/Dockerfile --build-context gradle=./backup -t backup . --no-cache
