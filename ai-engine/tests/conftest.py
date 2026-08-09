import os
import sys

# Make ai-engine's packages (core/, services/, rag/) importable when pytest
# runs from the ai-engine directory or the repo root.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
